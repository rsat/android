package org.owntracks.android.ui.map

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.*
import dagger.hilt.android.AndroidEntryPoint
import org.owntracks.android.R
import org.owntracks.android.data.repos.LocationRepo
import org.owntracks.android.databinding.GoogleMapFragmentBinding
import org.owntracks.android.gms.location.toGMSLatLng
import org.owntracks.android.gms.location.toGMSLocationSource
import org.owntracks.android.location.LocationSource
import timber.log.Timber
import java.util.*
import com.google.android.gms.maps.model.PolylineOptions
import org.owntracks.android.utils.GeoUtils

@AndroidEntryPoint
class GoogleMapFragment internal constructor() : MapFragment(), OnMapReadyCallback {
    constructor(locationSource: LocationSource, locationRepo: LocationRepo?) : this() {
        this.locationSource = locationSource
        this.locationRepo = locationRepo
    }

    private var locationRepo: LocationRepo? = null
    private var locationSource: LocationSource? = null
    private var googleMap: GoogleMap? = null
    private var binding: GoogleMapFragmentBinding? = null
    private val markers: MutableMap<String, Marker?> = HashMap()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.google_map_fragment, container, false)
        MapsInitializer.initialize(requireContext())
        val mapView = this.binding!!.googleMapView
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
        return binding!!.root
    }

    override fun onMapReady(googleMap: GoogleMap) {
        this.googleMap = googleMap
        if ((requireActivity() as MapActivity).checkAndRequestLocationPermissions()) {
            initMap()
        }
        ((requireActivity()) as MapActivity).onMapReady()
    }

    fun setMapStyle() {
        if (resources.configuration.uiMode.and(Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
            googleMap?.setMapStyle(
                MapStyleOptions.loadRawResourceStyle(
                    requireContext(),
                    R.raw.google_maps_night_theme
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun initMap() {
        this.googleMap?.run {
            isIndoorEnabled = false
            isMyLocationEnabled = true
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.setAllGesturesEnabled(true)

            if (activity is MapActivity) {
                if (locationRepo == null) {
                    locationRepo = (activity as MapActivity).locationRepo
                }
                if (locationSource == null) {
                    locationSource = (activity as MapActivity).mapLocationSource
                }
            }

            if (locationSource == null) {
                Timber.tag("873432").e("No location source set")
            } else {
                setLocationSource(locationSource!!.toGMSLocationSource())
            }

            setMapStyle()

            if (locationRepo?.currentLocation != null) {
                moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(
                            locationRepo!!.currentLocation!!.latitude,
                            locationRepo!!.currentLocation!!.longitude
                        ), ZOOM_LEVEL_STREET
                    )
                )
            } else {
                moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(
                            MapActivity.STARTING_LATITUDE,
                            MapActivity.STARTING_LONGITUDE
                        ), ZOOM_LEVEL_STREET
                    )
                )
            }

            setOnMarkerClickListener {
                it.tag?.run { (activity as MapActivity).onMarkerClicked(this as String) }
                true
            }

            setOnMapClickListener { (activity as MapActivity).onMapClick() }
            setOnCameraMoveStartedListener { reason ->
                if (reason == REASON_GESTURE) {
                    (activity as MapActivity).onMapClick()
                }
            }

            val list = GeoUtils.polylineDecoder("fhsu~@fphoeCsDzpA~\\vBkCbt@cBvo@gEjnASbGwBnn@kHbaB{@jWS~M?rDSzOf@jWz@fYbBrSfEn_@nFj\\vBfJjMnd@nFrNfJzTvBrDz@vBrIrSzJzOrI~MnKvQfEvGrDbGzOvQzE~HnF~HfJ~MvL~RrIzOnKvV~HrSvG~RRf@fEbQrIzY~CzJrDjMzEbQf@rD~H~a@bGfY~H~f@~M~dArDzYvGvj@zEfYvBjMrDzTrDvL_Ig@{@?_]bGgJnAcBf@{m@zJsb@vGs]bGwQjCgEf@wLz@wLf@gTf@_Sf@{^z@sb@z@kC?kCRgw@z@cV?gr@{@_D?gfAcBo}@{@gTf@_SbBcL~CgJjC{OvGkHfEgJbGka@nZkCvBsD~CkCvBk\\jW_g@v`@wGnFkMzJsDvBcBnAkCbBkCnAcBnAcBf@wBz@{EvB{EbB_g@~R_Dz@wBz@sDz@wBz@gJnAcBRkCf@cBf@oAf@wBf@kCz@kCnA_DnAsSrIkCnAoUfJcBf@fE~Mz@~CjCzJbQbo@rDvGrDrIjCrIbGrSbVfr@~p@n~Bf^bwA~u@v}Cn`B~vGzkAn}ErN~k@zOj_AnP~p@rNfm@fE~RnPnn@zErSfEjRzE~WrIv[vGbVjHbQvGjRnFbQ~HjWzEbQ~CnPf@jCjC~HbBbGvGjWfJr]bBnFjHfYnFrSrIrXjCnKrDbLfT~u@fYf|@rD~MfOrg@jCfJjCjHz@jCb[j_AnUvt@jCrIj\\~_AnKb`@rNbe@zOrg@rDnKf@bBvBbGrDjMjMjRjCzEjCzEzEvGrDfJrDrNfEjRnFzTbBfO~CzYzEfc@~CzYjCzO~CzTjCfTbB~MvGv`@fEjRzEfTfYjdAvGrXbQrq@jRnx@rg@~qBgw@jp@ka@wy@kM_XwG_Nw[wo@{kAzaAoAz@cBbBoAvB~\\z^jHrI~HfJbBbBvL~Mrv@vy@fOnP~RnUnFzErNzOrDzE~C~CnFbGvGjHzEnFvo@fw@fOnU?vBR~Cf@~Mz@fO?z@nAbQnA~RRfEz@bGz@zEnAbGnAnFnAfEvBzEnA~CvBrDvBzEbBrDjCvGnAfEf@bBf@jCnAnF~H~\\zErSbBrIbBvGz@~Crb@rhBvBfOf@rDRbBf@vBbBrDz@jCnAjCnAbBz@nAnAvBz@z@jCjCbBbBnAz@nAz@nAf@zEjCvBf@vBf@nAf@z@RvBRzEz@jHf@zJRfERnA?bBRnARvBf@vBf@bBRbBf@vBRrDnAzEbBnAz@z@f@z@f@jCbBnAz@nAnAbBvB~CrDnAvBbBjCbBvBz@vBnAvBz@vBf@vBz@vBf@vBf@nAf@bBRbBf@jCf@vBRbBRbBf@rDRrDRbB?bBRjC?~Cf@vLRbGRfERvGf@bGz@fEz@fEf@~Cz@~CnAzEf@vBf@nAnArDbBrDvBfEvBrDvBrDvB~CvBvBnAbBjCvBjCvB~CvBjCbBzOzJ~CnA~CbBz@z@R?nPzJfE~CbBbBvBvBnAnAbBvBnA~CnA~CnAbGf@nFRnFSnF{@rI{@zEcBzEwBzEg@z@oAjCoAbBcBjC_N~HkW~M_]fO{YnKc[~HkWnFgYvBgYz@oZSwVcB_]{EgYcGo_@oKgcEcmAwj@gOcVoF{TgEcVwB_S{@oPSwQf@wQz@_SbB{OvBoKjC{JvBcL~C{JrDgObGwLbGkWrNsSrNwQrNcQfOgTrSwQ~R{EzEgEzEkWrX{OfOkMjH{EfEwGbGkHfJ{Tb`@cBrDcBzEkCvLcBbLoAzJcBzJgEve@kCvQcBnKcBfJoAjHkCnKoK~a@_Nv`@s]nbAkf@jiAwLvVk\\b~@{JrXsNv[jRn_@jCnFbGnFnFrDnUbB~dAfE~WbBvGfEnUfO~M~Hrg@f^v~@zm@~HfEzh@f^~f@nZR~C?jCS~HcBf^{@zJg@jHg@zE{@bG{Eb[gJvo@cBbLwV~bBoK~u@kMb~@oZvuBwG{@cj@kMwj@kM_l@gOo_@{J_SS_X?kRz@wBg@RcBSbBsIbj@zJbmASvG{@rDcGf^cGv[{EzYnZzJoFn_@_Izc@wBrDoFv[kHnd@{@bG?~C?vLSrDgJj\\gm@{TkC{@wG~\\_Ij\\wQby@{T~_AkH~\\kRv~@S~M{@z^g@v`@?z^oAbe@w`@SoArv@SzJfTRzER~CzEoAb`@g@v`@SfTSjMSzYS~HSvBg@r]{@~a@?~HSjWg@zOSfEg@rb@g@r]SzEoArq@~Wg@zYSrIS~CS~H?rNSzYg@zJRnFz@fEz@fEnAfEbBbQjH~Cz@R?jCz@jCf@zEf@jHz@bB?zOnAfTnA~f@~CnFf@zE~k@vB~WnKvmARzE~Hv~@~C~a@rSv_Cf@vGRnAvLzpAvLjxArInlAjCz^f@jHnAzEbBnKjCbLfEnKRf@z@jCvBfEjCnFnA~Cf@z@~\\nx@vBnFjCbGfEnP~C~MjCvV~CbVf@vGr]brAja@r~Arb@r~Ajf@fiBzYfkAzEvQfEnP~CrNbGnUfEfTrNni@jHfYnd@{TbG_Dz@g@jMwGbG_Dja@gTn}@gc@~H_DjHwBzE{@zEoAzJcBvLwBvLcBnASjCSfT{@jdAkHzfAcLjMoAbzBoKjyCcVbGSrmBgObj@gEfJg@fr@{ErNcBzJcB~RgEzJkCvGcBzEcBf@SbGwBjMcGbLcGbL_IrIkHjMcLrIcGvLkMjMgOjRoZzT{c@~Rka@fEkHzJcQzEkHnKkMfJ{JnPkMbt@{h@~dAku@jW_SvLsIjWcQ~C_DjCkC~CsDjCgEjC{EfEkCvBcBvBoAjCoAjC{@jCg@jCf@~k@jMbLjCjHbBrIbB~Cf@~Cf@vGf@jMnArDRvBnAz@f@RRRf@f@f@Rz@?z@RnA?vB?bBSfE_Sz|@kHnZg@vBsb@ziBcQrq@kHvV{@~Cg@jCSfESjH?~HRvGf@fEf@fEz@zEvBbGSrD?vBSbBSbBoAfEwBbGcBrI{@vGgE~bBcBr`A{@vVg@~a@RrISzES~Cg@fEgEvLwQbe@gEzJwBbGcGbQcj@buBcBbG{@jC_Srq@sDrN{Ojk@sDnKwBfJkH?jH?kHfY{@zEcBrIsDvVsDfY{@fEg@fEwBbLcBjHoFja@_DvV{Ezh@cGn}@_Dbe@cG~z@wBnK{Tf|@_DnKod@rhBkCzOwGn_@sDbVsNvmAoAjHg@fEcBbLkCrNsDrSg@jCkH~f@rDf@vBf@fw@fOrDz@~Cf@~k@bLjCf@~Cf@jp@~MjCf@j_AnPfEz@f@?~HbBja@rIbe@fJjCf@rDz@r`AvQ~Cf@~Cf@b[bGvLvBjCRvBf@vBRvBRjCRfES~C?zJRrSf@ni@vBffAzEnlAzEf@rXbB~p@RjCSkCcB_q@g@sX{@gc@{@wj@wB_g@wBsl@oAod@cLkqBkC{w@oAsq@Sgm@g@on@SwmAbBknAbBgr@z@_Df@oAbB{@zEoArv@nAzaAbBvLRjM?~R?ja@{@zm@oAfm@cBb`@g@~Mf@nK?~HSvG{@~MkC~_AkRjCg@zE{@nFg@bBSvGg@jH?vG?nd@nAj\\bBns@jCvLRrb@vBb`@z@f^bBja@f@~CRzE?~W?vGSvGRnFRby@~Cnx@~Cvo@jCrq@bBzOz@rXz@vB?~Hg@zEg@vGoAjHoAzOcGzEwBvGwBfJ_DfJoA~\\gJjC{@bQgEr|B_g@rD{@jHcBoF{^oKsq@oFo_@kHnA_Dz@wGnA{w@zOcGnA{JjCsSzEoPrD{O~CrIz|@~CfTf@fEzEv[z@jHrIrl@vBjMvLju@rDvVjMby@~CbQjMnx@~CjRjHfc@nFf^RvBnFrS~CvLbQrq@vQfw@nZnlAfEzObQzm@vQvj@~CbLrNbe@bG~RbBnF~Wnx@vBnFbGrNbBrDjCbGrv@_DfJSnK{@fESjCSve@wBrIg@jCg@bGSnFg@rSg@~Rg@jz@wBnFSzh@oAfc@oAbe@cBnd@oAzc@oAfc@oAz^oAf^n{ARz@vGjWfEvQbBbGbGvVfEfObBzErIrSrDjHrNv[nKbVnKnUzJzTnKfTnFvLnF~M~Mz^vj@v|A~xAz~DrInUfTrl@zOj\\~Rf^~Wjf@bQzYbQb[jH~MnFzJjCfEbBrDz^jp@fTb`@fEjHvBrDfEzEnFnFrDjCbBnAbG~CzEnAfEz@fT~CzER~HnA~u@jHjMbBjsAzOv~@nK?~CRjCbBfEfJnKzEvG~CjCzTnZjMzO~MbQ~MbQzOjRfOjRbLrNrNvQ~CrDgOjRgYz^{OfTwj@vt@jMfO~MvQfObQfO~RjMzO~MvQ_l@nx@kM?_I?gaA?{T?wQS{@?{pAS?zY?z^g@zToAnUwBnUkCv[oAbQz@bB~CnFfErIvQnZzEzJvBnFz@zEzEf^jHzm@zJnx@f@vBvGjk@R~CsXbG{@RfEnZ~CzYf@fEz@bGoF~M_l@w`@~k@v`@nF_N{@cGg@gEoZsSsXwQ{@_DkC~CsI~RcLrXgOn_@olAcy@we@{^wj@_b@cBoA{h@_b@gEsDgEgEgJgJ{@oAsDfEkCjCcBbBoFrD_DvBkCnAwBRgEf@c}CrDgE?{m@vB{ERgYbBc[bBwQnA_Sz@sSnA_SnAkWnA{@?c`@bBsSz@sSnA_Sz@k\\bBkC?cBRg@fJwGvo@sDb[sDb[cBzO{@rIsDfYSvB_DrXsD~\\sNvrAg@vBg@bGwLffA_NzkAcLv~@oFz^{@bGoAfJwBzTwBzTkCzTkCjWoPbaBwBzTwBfTcLzkAoU{E{O_DcQ_D_SsDon@cLsl@oKsIcBolAgTgE{@kz@gOsDSsDSoFf@{Ez@oFbBoFjC_IvGkH~HgEjHgErNsDjW_Iju@wB~W{Ev`@sDvQoFnK_IfJsIfEwQvGka@{@_SSwL{@g@rDg@fEsSvaBg@bGkf@rgEzYzEbB_NcB~M{Y{Eg@nF{Jjz@k\\vnC{@bG{Jn}@_Dv[_DfYkCfT_Db[_DfYgJv~@g@nFoU~qBs{@naIg@zEg@fESjCjC~CrSnPrl@zh@sDrD_DjCkCbBkCvB{JbGcQvLoAz@wBnA{@f@oAf@oARoAf@cBR{@?cBRoA?oAS{@SoASoASoAg@oAg@{@g@_DwBwBoAcB{@wB{@wB{@wBg@wBg@wBg@wBg@kCSkCS_DS_D?{E?gE?cLf@kxAfEcGRgEcBcB{@oA{@oAwBoAgE?sDSgJ?cGSkH{@sX{@oPcBwVkC{YgEgc@{@sIoKgaAoKkdAoU_|Bn`BsXzc@sInU{EnUoFzT{EvB{r@bB_l@?{ERoFRsIf@{ErDgYzEc`@z@kHvBsN~CgY{TsDgO_DfO~CzTrD_DfYwBrN{@jH{Eb`@sDfYg@zESrISnF?zEcB~k@wBzr@fr@gOf@bBz@z@f@f@z@f@bBf@vGz@vBf@nARz@f@nAf@z@f@z@f@z@nAnAnAf@nAf@bBf@bBRvBRvB?~C?zESjRkWbzBkf@fmEoi@vyEwG~k@wGjk@wBbG{@bB{@vBg@nA{@z@g@z@{@f@{@z@{@R{@RoAR{@R{@?{@?{@?{@?{@Sg@S{@g@g@g@{@g@g@g@{@{@g@{@Sg@g@oAg@oASoA?oAS{@?oA?oAR{@?oARoAf@{@RoAR{@z@oAz@cBnAcBnAoAfm@w`@vQwL~k@{^bVgOf^oUrS_NrIcGjH{EnF{EvGcGjHcGnFgEfEgErIsIfEgEfEoFzEoFzEoF~k@gw@b_C_uC~tC_nDn{AsmBrDoFbGkHn_@kf@fOwQjMcLjRoPnP{JbLwGnPkH~R_IngAcVjz@cQns@gObt@oPz|@_Sbt@sNzr@{Org@wLjiAsXvy@oPj\\kHnKkCbL_DfJkC~HkCfJsDfJsDjHkCzJ{EzJgErI{Ev[oPrb@cVni@_Xf_B_{@r`Akf@zw@ka@~k@c[bo@oZ~xAwt@ni@_Xb`@cQbe@_SfYwL~MwGnn@oZn}@_b@zEwBjCoA~RgJjHsDzJoFfJoFnZcLrIkCbGcBjC{@fEoAjHcBzJsD~f@oUfc@kRvGSfESjCRbQvBzr@nKnZnAjHSnPcBjCg@j\\fYjWjWnZja@jMvQjR~RjWfYnFrD~CrDzErDnPbLjCbBfYnPbQbLzJ~HfEvGnKjRrIbQz@vBrDbGbBjCvGjHnFnFjCvBzE~CzOvGbLfEbL~CrIbBrDRzEf@bLf@bGf@zOf@vLf@j\\R~C?vVR~M?fm@RrN?rS?vL?nZSrjA{@z^SvB?zw@RrmB{@fc@S~vB{@vBS~W?b`@?v[Szr@RfkARbt@g@b`@R~CRjC?rD?vB?nFRju@Rbj@RrXRjWg@z@{@nAoARg@nAkC{@{@g@Sg@SoA?sg@?gY?kH?kHRcLRwVRsXRcB?cBz@sDSwB?g@?gE?kCSgYSkf@?_SR?f@?nAR~Cbt@g@b`@R~CRjC?rD?vB?f@oF?sIf@os@?gJRkW?oA?oPRkp@f@sb@z@os@RkMz@_b@z@kp@z@co@jC_cBRsNnAwy@Rk\\{@wVoA_q@?kC{@{Y{@gc@{@kf@g@k\\{@g^g@{T?{JSsIoAsI{@sD{@oFoA{EcGwQoA{EcBsIoAkHSoFg@_ISoFRoZSkMoA{^?sNS_N?_Sz@kMnA{JjCoK~Cg@bBRnAz@bBvBoAfY{@zYSf@Sz@Sf@g@R{@Rg@?{@?_I_DsDoAwBg@sDoAka@sNcj@_S_SkHo}@w[{aA_]{c@{OseAc`@oZ{Jw`@gObV{pAnF_]nKwo@bGk\\fEkWfEkWjHo_@bG{^rD_Sf@_DS{h@n_@_mB~Hs]z@{E~CkMbG_SfEcLzEwLfO{Y~MsS~HoKrDsDnzDcoEj}AcfBv`@sb@zh@_q@~\\ce@vmAshBbQsXrIsNvG{OvLgYnKk\\ve@_cBni@wpBjW{r@rD{JzE_NfJwVnKw`@rNsg@bQco@ja@wwArDkMvGkWfOco@~CsIbB_DbB_DnAwBbBcBbBcBnAoAvBcBbBoAnA{@bB{@bB{@bB{@vBg@bBg@vBg@vBg@jCSrDg@bGSzJ{@rl@sDvGSfE?vB?jCRvB?bBR~Cf@vBRvBf@jCz@rIvBfc@jMvVrIbQzEnFnArg@rIrDf@jWjCf_BvGzOz@~M?r]nAbGSvBg@vBg@nAg@vB{@rDcBrDoAbB{@vBg@vBg@vBg@~CSfEg@zJg@fTf@nPf@vLf@vLf@vLf@zJz@bLf@~MnAfJz@fh@nFzObB~W~CvLnAnKbBbLvBjR~CvrAbVjlB~\\rSrDnFz@fEz@vGnAbGz@zJbBfJnArInAfJnAzJnAvLnArNnArIf@jRnAnUz@fJf@bLf@fOf@bLf@nKf@~H?~Mf@zORrv@z@jnAz@nx@z@vo@f@vLRzYf@fORjMRrNf@rNf@vLf@rNf@jRz@r]vBnUbBnZvBrcBzO~RvBju@jHroAnKzm@bG~WvBfJnAn_@~CjRbBvQjC~M~CnPnFjMfEvLfEjRbGnZjHrSfE~Hz@zJf@bLf@zYRbt@oA~CSrD?rDSfJSzJSnKR~Hf@vQz@ryA~MzyDr]b{Dr]ju@bGbt@nFbkBrNzTbBrtAzJziBfOvsC~Wr`AjHnlA~H~gBbLv`@jCjz@bGnFRjCR~Hf@vrA~H~WzJfTzJrSbGvGbBnd@jCjHf@fJbBrDf@fEnAnF~CbG~CfEjCfEnArDnAzEz@fOnAfTnAzEg@~C{@vBoAnAoAf@{@f@sDRsIwBkp@SsI?sI?wLRcLf@kMf@wLz@gJz@sInAkMjHcj@bBwLbBwLbBgJvBsIrDoPnAoFf@gEbBsI~CsS~C_SnU{zAzOgfAvQknArSwrAzEoZbLsv@jRsoAzEk\\rDgYnFk\\bGka@RsDf@wGz@oPz@gJf@sIz@wGf@oFnA_IjCgOjCgOvBgJvQ_v@rIwj@RoAzJ_q@rX{_B~Hgr@jH{h@rS_tARoA{@oAoA{@sN{Jg@oAg@oAwV{O{@jCnKvGzJbGz@Rz@?gTf_B?zERnKRjC?bGwGzc@{Ef^_SbrAoArI{Jfr@sIjp@gEnZ{EnZoKrl@oAnF_DzJwLb`@{@rDcBnF{@nF{@nFgEjWsSjsASnA_Izr@oAjRoUryAsSjsAbQfEbt@jRzr@vQni@~MjWvGfnBzh@ns@~Rb`@nKzY~HraCzm@zTbGbBbBrXnZ~p@ju@~p@vt@~Wb[nAbBnAnAb[~\\bGsXnUwhAvQ_{@nU{fAzTchAnKkf@~Hc`@bQ_{@~CoPbBsIvVchArSchAkdAwVoZsI{TcG{r@cQ_NsDw`@{Jod@wL_`AoU_XcGgc@cL{@Sox@sSkCg@sD{@gJwBwj@sNs]sIkz@sSgToFoK_DS?cBg@{OsD_IcBsg@wLsNgE_Dg@kCg@_DSkCSkCRod@nAw[z@_]bBcVz@ogAjCgaAzEwkBbGwBR_Df@oFf@wBRwBf@kCnA{EjCgEbB{@z@kCz@cBf@kCf@_Ig@gEg@oFg@kCg@wB{@kC{@oAcBoAcBoAwB{EgJwBsDoAcBcBwBoAcBcBoAsDkCwL_IwV{O{c@gY_l@c`@{@kCg@oASSsg@s]kqB_tA{@g@{h@_]wLkHgEkCgJoFgOoKoPcLsXwQgkAsv@sS_N_DcBc[_Skk@c`@k\\gT_b@{Yc`@wV_S_NsNgJgJwG_XcQkCoA_IoFwt@_g@k\\sSkxAgaAobA_q@cy@{m@cB{@kMcL_{@sq@_XgTgEsDka@oZsXwQco@kf@w[oUoUwQsb@w[sq@we@gm@ce@c[cV_]cVsg@o_@g^wVgh@g^wo@{c@oK_Io_@sXwe@g^_l@gc@gc@c[cBcBoU{OcG{E_IoFcj@w`@cBoAc`@{YoFsD_DkCoUoPsIwG{EsDcBoAgE_DcL_Ice@_]_g@g^_b@gYc[sScQkMc`@{YgOwLwLsIgOoK{^_XgJwGsSgO_b@c[gJkHgc@w[sDkC{w@{m@g|@gm@kMgJsNcLs]sX{EgEkCwBsDcB{EwB_IwBos@gO_eAsSoF{@ccAsNwBS_]cGc[oFgOkCoASkCg@cVsDcBSkCg@{JoAsg@sIoZ_DgE{@oFoAgOkCce@_I{E{@od@_Isl@oKs]cG_q@wLkRsDksA{TcQ_Doi@{Jsg@_IcV{Ew[oFsSsDw~@{Owe@_I_q@wLkz@{Ood@sI{E{@sg@oKwe@gJgw@_Nsg@{Jk_AoPs{@cQ_l@oKgO_Ds{@{O{O_Dkk@cLg|@{OcVgEsSgE_SsD{m@cLkf@gJwy@{OkRsDwQgEwG{@gJcBwGoAkk@gJs]wG_D{@sX{Egc@_Ic[cGoF{@sv@sN{T{Ect@kMoUgEsl@oKg^cG_D{@_Dg@gJcBoAg@_q@kMs]kHw`@kH_g@{Jc`@_I{@vG_Ifh@sDjRoF~\\_SffAzT~H~Cz@rl@fTf|@b[zTwmAjR_oArDkWz@cGs]kHw`@kH_g@{Jc`@_I_IcBsq@kMchAsSoPsDwV{EsNkC{c@sIoUgEw`@kHon@cLgY{Ewy@sNoF{@wcAkRgJcB{fAgOgJcBwG{@{w@kMjR_~AnK_{@nFce@fEw[nK_{@g^cGf@gEfEk\\gEj\\g@fEoASod@sIkCrSkHbj@_D~WoFbe@{@~H_Ivo@_Sn`BwBg@_`AoPg_BoZkCbG_DzTSnA?RSf@Sf@g@?oAf@gm@oKcBS_v@sNsb@sIs`AcQkHcBg@bGoF~f@oAbLcBfOsDnd@wBSkCS_Df@wBnAkCbBoAbB{@bB{@jCg@jCSzERzEnAnFnA~Cf@RnAbBvBbB~Cf@oFnd@{Ebe@oFbe@syAs]_DRod@bBgc@nAsD?gJrcBSvB_X_DcLoA_NkC{JkCgJkCkRkHwQwGsb@gOs]oK{JcBoKcB_I{@sI?kM?cLz@kHf@oFf@{EnAwGbBwGvB_NrDkMfEcLfEoKnFwLjHkp@~a@{TvLkHfEsNnFwQjCgTnAwt@z@c|Az@sb@z@_`FrIwLf@{c@z@_v@jC{nBnFg^z@oPRgE?oF?sISwGSoi@kCkMg@gToAkMSwL?{JRkWnAsSnAgJz@sIz@oPfE{JrDcGjCsDbBc~@fh@gEbBsDnAsDf@oFz@kHz@_NvBkk@vLwLjCsXnFcBf@sDf@{Ez@kHnAsyArXccAvQcVzEoFz@{O~CwBf@oAf@cBf@oAz@kCz@kCnAwBz@kCnAcLvGkCbBwBz@cBf@oAz@cBf@gEz@_Dz@kM~CkCz@S?oAf@oAR{@f@{@f@oAz@{@z@cBnA_DjCcBnA{@z@oAz@{@z@{@f@{@R{@f@oAf@{@RcBRwBf@wBRoAR_DR_]wdCoPogAwLwy@kMwt@wVstAkH_b@SwBsDoUwB_NgEoZoAgJwBoKsDwQgEwQ{@_D{@gE{@gEg@{EcL_l@kC{OgE{YoKkk@{Jgr@g@wBgEk\\oAwGoAgJ_N_`A{Ek\\oFwe@sD{O{E_XsDwQgE_S{EsSoFcQsDoK_DkHcGwL{JkRkRc[kCgE_v@knA{@oAgEkHcVc`@cQ_Xs{@stAkHcL_IwLwB{EkC{EkCgEwBoFwBgEkCcGwBcGwBcGkCsIwB{JcBsI_Ngw@SoAScBScGSkC?{ERgO?gESwBS_Dg@gEg@cB{@cBg@cBoAoAoAg@oASoAS{@?g@?{@?{@R{@R{@RoAf@oAz@cBnAg@z@g@z@{@vB?nARjC?fERz@f@vGf@vGf@vBRjCf@~Cz@rDf@~CRnAz@zEgEz@{JnA_ISz@kCRwBR{@R{@RwBR{ERgERcLR{ORsI?oF?gESgE?_Dg@sDg@kCg@kCg@kCg@kCg@kCoAwB{@wBoAcBoAcBoAoAcBoAwBcBwBoAsIgEwB{@oAoAcB{@cBoA{@oAoAoAoAcB{@cBoAwBoAwB_IkRw[_{@wmA{jDgE_IgEoFgw@s`Aco@{m@_`A_{@{E_DsDwBwy@od@{JoFoUoKw~@w`@wwAkf@{r@oUwQwGwGwB{EwB{EkC{EkC{E_DcGsDcGoFcGsD{EgEcGoFsDsD_IgJoFoFwGsIkHoKoFsIsDwG{EgJgEgJoFcLoFsN{EkMkCgJ_DkM_DkMsDgOsD_SgE{TcQw~@kMgr@sXwwAoF_XwGwV_IcV_IgTgJgTsNgYcL_Sc[kf@sXgc@cLoPsIkMoKkMoKwL{OoPgTsSkW{TkMkMkH_I_IkHsIwG_IoF_I{EkH{EkHgEcBg@_SoKwGkCkHkCgJkCsIwB{JwBoKcBsNkC_]gEce@wGgYgEcQwBwLwBgc@kHwG{@oFoA{E{@kHcBcGcBkHkCwQgJ_IgEcGsDkHgEkHoFoF{EoFoF{E{EgEcGoF_IsDcGgEsI_DkHwBcGwBcGwBkHcBcGcBwGkCwLsDcQ{Ogr@wB{JcBwLcBwLg@wG{@sIg@{Jg@_S{@oi@g@sb@{@ka@g@oPoAcQ{@cLcBwLkCgO_N{h@_l@_|BwrAgfFgOon@wLwe@kHk\\oFoZoFw[_N{fA_N{fAsDcVkC{OgEgTSoK?oFRwGf@oFz@oFf@{EvB_If@cGRgERwG?gES{Eg@gEg@{E{@kCoAcB{@oAcBoAoA{@cBg@cBg@cB?wBScBRcB?cBf@cBf@wBnAcBnAkC~C_DnFgOrXrDjMoFbB{h@nKkf@zJwBf@cBkRsD{OwBcLoAoFkH{TkH{OkRgY_D{Ect@~\\{OfJs`Ave@c[fOsNbGwBz@cBf@{Y{kAwGgYcGwV{@_DkCoKwBkHkC{JkCwGkHcQsDsIwGwLoKwQg@oAwBsD{E_D{O_XgJ{OoFsIcL_SoPgYsDoF{O_XwBgEwGoPwB_DoAkC_l@s`AkM{Ton@seA{@oA{O_XsNwV{EsIsD_I{EcLsD_IkCwGoFkMcGwVwGgTwBcGcB_DoAwBkCoKg@cBg@wBRoARoARoA?oA?oA?oASoASoASoAS{@S{@g@{@g@oA{@{@g@g@{@{@{@g@{@g@{@g@oAS{@S{@?{@SoAR{@?{@R{@R{@R{@f@{@f@g@f@g@f@{@f@Sz@{@z@Sz@g@z@Sz@Sz@Sz@?z@Sz@?z@?nA?nA?nARnARz@RnARz@?nAg@vBSnASz@Sz@g@z@oAnAwGjH{@bBoAbB{@jCkH~H_DrDsIzJ{JvLw[n_@oK{JnKzJv[o_@zJwLrI{J~CsDjH_I~CcBnA{@z@{@nAoAjC_DjC_Dz@g@f@g@f@Sz@g@jCg@z@f@z@Rz@Rz@Rz@Rz@?z@?f@bBz@nAbBvBvB~CbBrDz@bB~HbLbGnKjCjCjHrInFrIzE~HrD~HjMnZ~f@~z@z@bBns@zkAju@roAnAjCvBrDnKvLvBfEzO~WrDnFnPfYbL~RnFrIfJzOzO~WnAbGnAvBz@nAbB~CfJrNvG~MrDjHjCvG~CrIbGjRbBbGvBfJnAzEjCzJvGnZrb@zdBRnAvBrIbVv~@vBfJvQju@f@rDwj@jRnFfYfh@cQvB{@z@~Cb[fpAbBvGfEzORnAjCvLfEzObQns@nFfTnFvVnArD~CvLjCjH~CzErDfE~HrIbBvBzEzE~MrNjHjHbe@rg@rDrDrDrDrq@vt@nUjWnAnA~\\z^jHrI~HfJbBbBvL~Mrv@vy@fOnP~RnUnFzErNzOrDzE~C~CnFbGvGjHzEnFvo@fw@fOnUrNfOrDrD~CrDfE~CbLnKvBjCbQfTrDfE~HrIzEzErDjCvGjCbGbBnFnAfJbBrq@zO~p@vQfE?ncC~k@fEz@vGbBjCf@ni@bLni@jM~HvBfc@vLbVjHja@nUvBz@zJbGnFbBfJ~CzYfJjf@jMrIjC~Cz@vQnFbQzEbGnAz@RfEbB~CvBnAbBnAbBvBjCfErIfJrXzEnPf@bBfJ~\\bL~WnArDnA{@~CwBf^kWbBoAjCwB~f@_NvL{@vVoFrNsDfO{E~C{@zO_DrD{@nAg@~sAoZz^_Izm@sNfE{@vV{Ez@S~f@wL~Cg@~C{@by@kRbQsDrSgEfc@oKb`@sI~Cg@f@fEbVvfBf@~CRvB")

            // Instantiates a new Polyline object and adds points to define a rectangle
            val rectOptions = PolylineOptions().addAll(list)

            // Get back the mutable Polyline
            val polyline = googleMap?.addPolyline(rectOptions)

        }
    }

    override fun updateCamera(latLng: org.owntracks.android.location.LatLng) {
        googleMap?.moveCamera(CameraUpdateFactory.newLatLng(latLng.toGMSLatLng()))
    }

    override fun clearMarkers() {
        this.googleMap?.clear()
        markers.clear()
    }

    override fun updateMarker(id: String, latLng: org.owntracks.android.location.LatLng) {
        val marker = markers[id]
        if (marker?.tag != null) {
            marker.position = latLng.toGMSLatLng()
        } else {
            // If a marker has been removed, its tag will be null. Doing anything with it will make it explode
            if (marker != null) {
                markers.remove(id)
                marker.remove()
            }
            markers[id] = googleMap?.run {
                addMarker(
                    MarkerOptions().position(latLng.toGMSLatLng()).anchor(0.5f, 0.5f).visible(false)
                ).also { it?.tag = id }
            }
        }
    }

    override fun setMarkerImage(id: String, bitmap: Bitmap) {
        markers[id]?.run {
            setIcon(BitmapDescriptorFactory.fromBitmap(bitmap))
            isVisible = true
        }
    }

    override fun locationPermissionGranted() {
        initMap()
    }

    override fun removeMarker(id: String) {
        markers[id]?.remove()
    }

    override fun onResume() {
        super.onResume()
        locationSource?.reactivate()
        binding?.googleMapView?.onResume()
        setMapStyle()
    }

    override fun onLowMemory() {
        binding?.googleMapView?.onLowMemory()
        super.onLowMemory()
    }

    override fun onPause() {
        binding?.googleMapView?.onPause()
        locationSource?.deactivate()
        super.onPause()
    }

    override fun onDestroy() {
        binding?.googleMapView?.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding?.googleMapView?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        binding?.googleMapView?.onStart()
    }

    override fun onStop() {
        binding?.googleMapView?.onStop()
        super.onStop()
    }

    companion object {
        private const val ZOOM_LEVEL_STREET: Float = 15f
    }
}

