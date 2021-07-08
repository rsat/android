package org.owntracks.android.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.schibsted.spain.barista.assertion.BaristaVisibilityAssertions.assertContains
import com.schibsted.spain.barista.assertion.BaristaVisibilityAssertions.assertDisplayed
import com.schibsted.spain.barista.assertion.BaristaVisibilityAssertions.assertNotExist
import com.schibsted.spain.barista.interaction.BaristaClickInteractions.longClickOn
import com.schibsted.spain.barista.interaction.BaristaEditTextInteractions.writeTo
import com.schibsted.spain.barista.rule.flaky.AllowFlaky
import org.junit.Test
import org.junit.runner.RunWith
import org.owntracks.android.R
import org.owntracks.android.TestWithAnActivity
import org.owntracks.android.ui.regions.RegionsActivity

@LargeTest
@RunWith(AndroidJUnit4::class)
class RegionsActivityTests : TestWithAnActivity<RegionsActivity>(RegionsActivity::class.java) {
    @Test
    @AllowFlaky(attempts = 1)
    fun initialRegionsActivityIsEmpty() {
        assertDisplayed(R.string.waypointListPlaceholder)
        assertDisplayed(R.id.add)
    }

    @Test
    @AllowFlaky(attempts = 1)
    fun whenAddingARegionThenTheRegionIsShown() {
        val regionName = "test region"
        val latitude = 51.123
        val longitude = 0.456
        val radius = 159
        clickOnAndWait(R.id.add)
        writeTo(R.id.description, regionName)
        writeTo(R.id.latitude, latitude.toString())
        writeTo(R.id.longitude, longitude.toString())
        writeTo(R.id.radius, radius.toString())

        clickOnAndWait(R.id.save)

        assertDisplayed(regionName)

        clickOnAndWait(regionName)

        assertContains(R.id.description, regionName)
        assertContains(R.id.latitude, latitude.toString())
        assertContains(R.id.longitude, longitude.toString())
        assertContains(R.id.radius, radius.toString())
    }

    @Test
    @AllowFlaky(attempts = 1)
    fun whenAddingARegionAndThenDeletingItThenTheRegionIsNotShown() {
        val regionName = "test region to be deleted"
        val latitude = 51.123
        val longitude = 0.456
        val radius = 159
        clickOnAndWait(R.id.add)
        writeTo(R.id.description, regionName)
        writeTo(R.id.latitude, latitude.toString())
        writeTo(R.id.longitude, longitude.toString())
        writeTo(R.id.radius, radius.toString())

        clickOnAndWait(R.id.save)

        assertDisplayed(regionName)

        longClickOn(regionName)

        clickOnAndWait("Delete")

        assertNotExist(regionName)
    }

}