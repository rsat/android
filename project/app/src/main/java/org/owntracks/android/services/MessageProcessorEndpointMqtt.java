package org.owntracks.android.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;

import androidx.annotation.WorkerThread;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistable;
import org.greenrobot.eventbus.EventBus;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;
import org.owntracks.android.R;
import org.owntracks.android.data.EndpointState;
import org.owntracks.android.data.repos.EndpointStateRepo;
import org.owntracks.android.model.messages.MessageBase;
import org.owntracks.android.model.messages.MessageCard;
import org.owntracks.android.model.messages.MessageClear;
import org.owntracks.android.services.worker.Scheduler;
import org.owntracks.android.support.Events;
import org.owntracks.android.support.Parser;
import org.owntracks.android.support.Preferences;
import org.owntracks.android.support.RunThingsOnOtherThreads;
import org.owntracks.android.support.SocketFactory;
import org.owntracks.android.support.interfaces.ConfigurationIncompleteException;
import org.owntracks.android.support.interfaces.StatefulServiceMessageProcessor;
import org.owntracks.android.support.preferences.OnModeChangedPreferenceChangedListener;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

import static org.owntracks.android.support.RunThingsOnOtherThreads.NETWORK_HANDLER_THREAD_NAME;

public class MessageProcessorEndpointMqtt extends MessageProcessorEndpoint implements StatefulServiceMessageProcessor, OnModeChangedPreferenceChangedListener {
    public static final int MODE_ID = 0;

    private CustomMqttClient mqttClient;

    private String lastConnectionId;
    private static EndpointState state;

    private MessageProcessor messageProcessor;
    private RunThingsOnOtherThreads runThingsOnOtherThreads;
    private Context applicationContext;

    private Parser parser;
    private Preferences preferences;
    private Scheduler scheduler;
    private EventBus eventBus;

    MessageProcessorEndpointMqtt(MessageProcessor messageProcessor, Parser parser, Preferences preferences, Scheduler scheduler, EventBus eventBus, RunThingsOnOtherThreads runThingsOnOtherThreads, Context applicationContext, EndpointStateRepo endpointStateRepo) {
        super(messageProcessor);
        this.parser = parser;
        this.preferences = preferences;
        this.scheduler = scheduler;
        this.eventBus = eventBus;
        this.messageProcessor = messageProcessor;
        this.runThingsOnOtherThreads = runThingsOnOtherThreads;
        this.applicationContext = applicationContext;
        if (preferences != null) {
            preferences.registerOnPreferenceChangedListener(this);
        }
    }

    void reconnectAndSendKeepalive(Semaphore completionNotifier) {
        if (!Thread.currentThread().getName().equals(NETWORK_HANDLER_THREAD_NAME)) {
            runThingsOnOtherThreads.postOnNetworkHandlerDelayed(() -> reconnectAndSendKeepalive(completionNotifier), 0);
            return;
        }
        try {
            if (!checkConnection()) {
                reconnect();
            }
            if (checkConnection()) {
                mqttClient.ping();
            }
        } finally {
            if (completionNotifier != null) {
                completionNotifier.release();
            }
        }
    }

    synchronized void sendMessage(MessageBase m) throws ConfigurationIncompleteException, OutgoingMessageSendingException, IOException {
        m.addMqttPreferences(preferences);
        String messageId = m.getMessageId();
        try {
            connectToBroker();
        } catch (MqttConnectionException e) {
            Timber.w("failed connection attempts: %s", sendMessageConnectPressure);
            messageProcessor.onMessageDeliveryFailed(messageId);
            throw new OutgoingMessageSendingException(e);
        } catch (ConfigurationIncompleteException e) {
            Timber.w("failed connection attempts :%s", sendMessageConnectPressure);
            messageProcessor.onMessageDeliveryFailed(messageId);
            throw e;
        }

        try {
            IMqttDeliveryToken pubToken = this.mqttClient.publish(m.getTopic(), m.toJsonBytes(parser), m.getQos(), m.getRetained());
            long startTime = System.nanoTime();
            pubToken.waitForCompletion(TimeUnit.SECONDS.toMillis(30));
            long endTime = System.nanoTime();
            long duration = (endTime - startTime);
            Timber.i("Message id=%s sent in %dms", messageId, TimeUnit.NANOSECONDS.toMillis(duration));
            messageProcessor.onMessageDelivered(m);
        } catch (MqttException e) {
            Timber.e(e, "MQTT Exception delivering message");
            messageProcessor.onMessageDeliveryFailed(messageId);
            throw new OutgoingMessageSendingException(e);
        } catch (IOException e) {
            // Message will not contain BUNDLE_KEY_ACTION and will be dropped by scheduler
            Timber.e(e, "JSON serialization failed for message %s. Message will be dropped", m.getMessageId());
            messageProcessor.onMessageDeliveryFailedFinal(messageId);
            throw e;
        }
    }

    private final MqttCallbackExtended iCallbackClient = new MqttCallbackExtended() {
        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            Timber.d("Connect Complete. Reconnected: %s, serverUri:%s", reconnect, serverURI);
            onConnect();
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {

        }

        @Override
        public void connectionLost(Throwable cause) {
            Timber.e(cause, "connectionLost error");
            scheduler.cancelMqttPing();
            changeState(EndpointState.DISCONNECTED.withError(cause));
            scheduler.scheduleMqttReconnect();
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            try {
                MessageBase m = parser.fromJson(message.getPayload());
                if (!m.isValidMessage()) {
                    Timber.e("message failed validation");
                    return;
                }
                m.setTopic(topic);
                m.setRetained(message.isRetained());
                m.setQos(message.getQos());
                onMessageReceived(m);
            } catch (Parser.EncryptionException e) {
                Timber.e(e, "Decryption failure payload:%s ", new String(message.getPayload()));
            } catch (IOException e) {
                if (message.getPayload().length == 0) {
                    Timber.d("clear message received: %s", topic);
                    MessageClear m = new MessageClear();
                    m.setTopic(topic.replace(MessageCard.BASETOPIC_SUFFIX, ""));
                    onMessageReceived(m);
                } else {
                    Timber.e(e, "payload: %s ", new String(message.getPayload()));
                }
            }
        }
    };

    private CustomMqttClient buildMqttClient() throws URISyntaxException, MqttException {
        Timber.d("Initializing new mqttClient");

        String scheme = "tcp";
        if (preferences.getTls()) {
            if (preferences.getWs()) {
                scheme = "wss";
            } else
                scheme = "ssl";
        } else {
            if (preferences.getWs())
                scheme = "ws";
        }

        String cid = preferences.getClientId();

        String connectString = new URI(scheme, null, preferences.getHost(), preferences.getPort(), null, null, null).toString();
        Timber.d("client id :%s, connect string: %s", cid, connectString);
        try {
            CustomMqttClient mqttClient = new CustomMqttClient(connectString, cid, new MqttClientMemoryPersistence());
            mqttClient.setCallback(iCallbackClient);
            return mqttClient;
        } catch (IllegalArgumentException e) {
            throw new URISyntaxException(connectString, "Invalid URL");
        }
    }

    private int sendMessageConnectPressure = 0;

    @WorkerThread
    private synchronized void connectToBroker() throws MqttConnectionException, ConfigurationIncompleteException {
        Timber.d("Connecting to broker. ThreadId: %s", Thread.currentThread());
        sendMessageConnectPressure++;
        boolean isUiThread = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? Looper.getMainLooper().isCurrentThread()
                : Thread.currentThread() == Looper.getMainLooper().getThread();

        if (isConnected()) {
            Timber.d("already connected to broker");
            changeState(getState()); // Background service might be restarted and not get the connection state
            return;
        }

        if (isConnecting()) {
            Timber.d("already connecting");
            return;
        }

        try {
            checkConfigurationComplete();
        } catch (ConfigurationIncompleteException e) {
            changeState(EndpointState.ERROR_CONFIGURATION.withError(e));
            throw e;
        }

        if (isUiThread) {
            throw new RuntimeException("BLOCKING CONNECT ON MAIN THREAD");
        } else {
            Timber.d("Connecting on non-ui worker thread: %s", Thread.currentThread());
        }
        changeState(EndpointState.CONNECTING);

        try {
            this.mqttClient = buildMqttClient();
        } catch (URISyntaxException | MqttException e) {
            Timber.e(e, "Error creating MQTT client");
            changeState(EndpointState.ERROR.withError(e));
            throw new MqttConnectionException(e);
        }

        MqttConnectOptions mqttConnectOptions = getMqttConnectOptions();

        try {
            Timber.v("MQTT connecting synchronously");
            this.mqttClient.connect(mqttConnectOptions).waitForCompletion();
        } catch (MqttException e) {
            changeState(EndpointState.ERROR.withError(e));
            throw new MqttConnectionException(e);
        }
        Timber.d("MQTT Connected success.");
        scheduler.scheduleMqttMaybeReconnectAndPing(mqttConnectOptions.getKeepAliveInterval());

        changeState(EndpointState.CONNECTED);

        sendMessageConnectPressure = 0; // allow new connection attempts from queueMessageForSending
    }

    private MqttConnectOptions getMqttConnectOptions() throws MqttConnectionException {
        MqttConnectOptions connectOptions = new MqttConnectOptions();
        /* Even though the MQTT spec supports various different combinations of setting the username
        & password flags to allow and differentiate between no usernames, empty usernames etc. there's
        too many permutations of these to usefully expose to the end user. So, to simplify: if the
        username is not the empty string, send the username and password. If it is empty, then don't
        send either.

        This might change depending on what users want.
         */
        if (!preferences.getUsername().trim().equals("")) {
            connectOptions.setUserName(preferences.getUsername());
            connectOptions.setPassword(preferences.getPassword().toCharArray());
        }

        connectOptions.setMqttVersion(preferences.getMqttProtocolLevel());
        InputStream clientCaInputStream = null;
        InputStream clientCertInputStream = null;
        try {
            if (preferences.getTls()) {
                String tlsCaCrt = preferences.getTlsCaCrt();
                String tlsClientCrt = preferences.getTlsClientCrt();

                SocketFactory.SocketFactoryOptions socketFactoryOptions = new SocketFactory.SocketFactoryOptions();

                if (tlsCaCrt.length() > 0) {
                    try {
                        clientCaInputStream = applicationContext.openFileInput(tlsCaCrt);
                        socketFactoryOptions.withCaInputStream(clientCaInputStream);

                        /* The default for paho is to validate hostnames as per the HTTPS spec. However, this causes
                        a bit of a breakage for some users using self-signed certificates, where the verification of
                        the hostname is unnecessary under certain circumstances. Specifically when the fingerprint of
                        the server leaf certificate is the same as the certificate supplied as the CA (as would be the
                        case using self-signed certs.

                        So we turn off HTTPS behaviour and supply our own hostnameverifier that knows about the self-signed
                        case.
                         */

                        connectOptions.setHttpsHostnameVerificationEnabled(false);
                        try (FileInputStream caFileInputStream = applicationContext.openFileInput(tlsCaCrt)) {
                            X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(caFileInputStream);
                            connectOptions.setSSLHostnameVerifier(new MqttHostnameVerifier(ca));
                        }
                    } catch (FileNotFoundException e) {
                        Timber.e(e);
                    }
                }

                if (tlsClientCrt.length() > 0) {
                    try {
                        clientCertInputStream = applicationContext.openFileInput(tlsClientCrt);
                        socketFactoryOptions.withClientP12InputStream(clientCertInputStream).withClientP12Password(preferences.getTlsClientCrtPassword());
                    } catch (FileNotFoundException e) {
                        Timber.e(e);
                    }
                }

                connectOptions.setSocketFactory(new SocketFactory(socketFactoryOptions));
            }

        } catch (CertificateException | NoSuchAlgorithmException | UnrecoverableKeyException | KeyStoreException | KeyManagementException | IOException e) {
            changeState(EndpointState.ERROR.withError(e).withMessage("TLS setup failed"));
            throw new MqttConnectionException(e);
        } finally {
            try {
                if (clientCaInputStream != null) {
                    clientCaInputStream.close();
                }
                if (clientCertInputStream != null) {
                    clientCertInputStream.close();
                }
            } catch (IOException e) {
                Timber.e(e);
            }

        }

        setWill(connectOptions);

        connectOptions.setKeepAliveInterval(preferences.getKeepalive());
        connectOptions.setConnectionTimeout(30);

        connectOptions.setCleanSession(preferences.getCleanSession());
        return connectOptions;
    }

    private void setWill(MqttConnectOptions m) {
        try {
            JSONObject lwt = new JSONObject();
            lwt.put("_type", "lwt");
            lwt.put("tst", (int) TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));

            m.setWill(preferences.getPubTopicBase(), lwt.toString().getBytes(), 0, false);
        } catch (JSONException ignored) {
        } catch (IllegalArgumentException e) {
            changeState(EndpointState.ERROR_CONFIGURATION.withError(e).withMessage("Invalid pubTopic specified"));
            throw e;
        }
    }

    private String getConnectionId() {
        return String.format("%s/%s", mqttClient.getCurrentServerURI(), mqttClient.getClientId());
    }

    private void onConnect() {
        Timber.d("MQTT connected!. Running onconnect handler (threadID %s)", Thread.currentThread());
        scheduler.cancelMqttReconnect();
        // Check if we're connecting to the same broker that we were already connected to
        String connectionId = getConnectionId();
        if (lastConnectionId != null && !connectionId.equals(lastConnectionId)) {
            eventBus.post(new Events.EndpointChanged());
            lastConnectionId = connectionId;
            Timber.v("lastConnectionId changed to: %s", lastConnectionId);
        }

        if (!preferences.getSub()) // Don't subscribe if base topic is invalid
            return;

        Set<String> topics = getTopicsToSubscribeTo(
                preferences.getSubTopic(),
                preferences.getInfo(),
                preferences.getPubTopicInfoPart(),
                preferences.getPubTopicEventsPart(),
                preferences.getPubTopicWaypointsPart()
        );
        // Receive commands for us
        topics.add(preferences.getPubTopicBase() + preferences.getPubTopicCommandsPart());

        subscribe(topics.toArray(new String[0]));
    }

    @NotNull
    Set<String> getTopicsToSubscribeTo(String subTopics, Boolean subscribeToInfo, String infoTopicSuffix, String eventsTopicSuffix, String waypointsTopicSuffix) {
        Set<String> topics = new TreeSet<>();

        if (subTopics.equals(applicationContext.getString(R.string.defaultSubTopic))) {
            topics.add(subTopics);
            if (subscribeToInfo) {
                topics.add(subTopics + infoTopicSuffix);
            }
            topics.add(subTopics + eventsTopicSuffix);
            topics.add(subTopics + waypointsTopicSuffix);
        } else {
            topics.addAll(Arrays.asList(subTopics.split(" ")));
        }

        return topics;
    }

    private void subscribe(String[] topics) {
        if (!isConnected()) {
            Timber.e("subscribe when not connected");
            return;
        }
        for (String s : topics) {
            Timber.v("subscribe() - Will subscribe to: %s", s);
        }
        try {
            int[] qos = getSubTopicsQos(topics);
            this.mqttClient.subscribe(topics, qos);
        } catch (MqttException e) {
            changeState(EndpointState.ERROR.withError(e).withMessage("Subscribe failed"));
        }
    }

    private int[] getSubTopicsQos(String[] topics) {
        int[] qos = new int[topics.length];
        Arrays.fill(qos, preferences.getSubQos());
        return qos;
    }

    private void unsubscribe(String[] topics) {
        if (!isConnected()) {
            Timber.e("subscribe when not connected");
            return;
        }

        for (String s : topics) {
            Timber.v("unsubscribe() - Will unsubscribe from: %s", s);
        }

        try {
            mqttClient.unsubscribe(topics);
        } catch (Exception e) {
            Timber.e(e, "Unable to unsubscribe from topics");
        }
    }

    private void disconnect(boolean fromUser) {
        Timber.d("disconnect. Manually triggered? %s. ThreadID: %s", fromUser, Thread.currentThread());
        if (isConnecting()) {
            return;
        }

        try {
            if (isConnected()) {
                Timber.d("Disconnecting");
                this.mqttClient.disconnect(0);
            }

        } catch (MqttException e) {
            Timber.e(e, "Error disconnecting from broker");
        } finally {
            this.mqttClient = null;

            if (fromUser)
                changeState(EndpointState.DISCONNECTED_USERDISCONNECT);
            else
                changeState(EndpointState.DISCONNECTED);
            scheduler.cancelMqttPing();
            scheduler.cancelMqttReconnect();
        }
    }

    public void reconnect() {
        reconnect(null);
    }

    public void reconnect(Semaphore completionNotifier) {
        if (!Thread.currentThread().getName().equals(NETWORK_HANDLER_THREAD_NAME)) {
            runThingsOnOtherThreads.postOnNetworkHandlerDelayed(() -> reconnect(completionNotifier), 0);
            return;
        }
        if (isConnected() || isConnecting()) {
            disconnect(false);
        }
        try {
            connectToBroker();
        } catch (MqttConnectionException | ConfigurationIncompleteException e) {
            Timber.e(e, "Failed to reconnect to MQTT broker");
        } finally {
            if (completionNotifier != null) {
                completionNotifier.release();
            }
        }
    }

    @Override
    public void disconnect() {
        disconnect(true);
    }

    @Override
    public void checkConfigurationComplete() throws ConfigurationIncompleteException {
        // Required to connect: host, username (only send when auth is enabled)
        // When auth is enabled, password (unless usePassword is set to false which only sends username)
        if (preferences.getHost().trim().isEmpty()) {
            throw new ConfigurationIncompleteException("Host missing");
        }
    }

    @WorkerThread
    @Override
    public boolean checkConnection() {
        return isConnected();
    }

    private void changeState(EndpointState newState) {
        if (state == newState)
            return;

        state = newState;
        messageProcessor.onEndpointStateChanged(newState);
    }

    private boolean isConnected() {
        return this.mqttClient != null && this.mqttClient.isConnected();
    }

    private boolean isConnecting() {
        return (this.mqttClient != null) && (state == EndpointState.CONNECTING);
    }

    private static EndpointState getState() {
        return state;
    }

    @Override
    public void onDestroy() {
        disconnect(false);
        scheduler.cancelMqttTasks();
    }

    @Override
    public void onCreateFromProcessor() {
        try {
            checkConfigurationComplete();
            scheduler.scheduleMqttReconnect();
        } catch (ConfigurationIncompleteException e) {
            changeState(EndpointState.ERROR_CONFIGURATION.withError(e));
        }
    }

    @Override
    public void onAttachAfterModeChanged() {
        //NOOP
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (preferences.getMode() != MessageProcessorEndpointMqtt.MODE_ID) {
            return;
        }
        if (preferences.getPreferenceKey(R.string.preferenceKeyMqttProtocolLevel).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyHost).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyPassword).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyPort).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyClientId).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyTLS).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyTLSCaCrt).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyTLSClientCrt).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyTLSClientCrtPassword).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyWS).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyDeviceId).equals(key)
        ) {
            Timber.d("MQTT preferences changed. Reconnecting to broker. ThreadId: %s", Thread.currentThread());
            reconnect();
        }
    }

    private static final class MqttClientMemoryPersistence implements MqttClientPersistence {
        private static Hashtable<String, MqttPersistable> data;

        @Override
        public void open(String s, String s2) {
            if (data == null) {
                data = new Hashtable<>();
            }
        }

        @Override
        public void close() {

        }

        @Override
        public void put(String key, MqttPersistable persistable) {
            data.put(key, persistable);
        }

        @Override
        public MqttPersistable get(String key) {
            return data.get(key);
        }

        @Override
        public void remove(String key) {
            data.remove(key);
        }

        @Override
        public Enumeration keys() {
            return data.keys();
        }

        @Override
        public void clear() {
            data.clear();
        }

        @Override
        public boolean containsKey(String key) {
            return data.containsKey(key);
        }
    }

    private static final class CustomMqttClient extends MqttAsyncClient {

        CustomMqttClient(String serverURI, String clientId, MqttClientPersistence persistence) throws MqttException {
            super(serverURI, clientId, persistence);
        }

        void ping() {
            if (comms != null)
                comms.checkForActivity();
        }
    }

    @Override
    int getModeId() {
        return MODE_ID;
    }

    @Override
    protected MessageBase onFinalizeMessage(MessageBase message) {
        // Not relevant for MQTT mode
        return message;
    }
}

class MqttConnectionException extends Exception {
    MqttConnectionException(Exception e) {
        super(e);
    }
}