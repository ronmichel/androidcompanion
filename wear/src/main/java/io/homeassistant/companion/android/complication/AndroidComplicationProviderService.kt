/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.homeassistant.companion.android.complication

import android.content.ComponentName
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.R
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import io.homeassistant.companion.android.common.R as commonR

/**
 * Example watch face complication data source provides a number that can be incremented on tap.
 *
 * Note: This class uses the suspending variation of complication data source service to support
 * async calls to the data layer, that is, to the DataStore saving the persistent values.
 */
@AndroidEntryPoint
class AndroidComplicationProviderService : SuspendingComplicationDataSourceService() {
    
    private var entityUpdates: Flow<Entity<*>>? = null
    
    @Inject
    lateinit var integrationUseCase: IntegrationRepository
    protected var mainScope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())
    /*
     * Called when a complication has been activated. The method is for any one-time
     * (per complication) set-up.
     *
     * You can continue sending data for the active complicationId until onComplicationDeactivated()
     * is called.
     */
    override fun onComplicationActivated(
        complicationInstanceId: Int,
        type: ComplicationType
    ) {
        Log.d(TAG, "onComplicationActivated(): $complicationInstanceId")
        
        val thisDataSource = ComponentName(this, javaClass)
        // We pass the complication id, so we can only update the specific complication tapped.
        val complicationPendingIntent =
            ComplicationTapBroadcastReceiver.getToggleIntent(
                this,
                thisDataSource,
                complicationInstanceId
            ) 
        val context = this
        mainScope = CoroutineScope(Dispatchers.Main + Job())
        if (entityUpdates == null) {
            mainScope.launch {
                if (!integrationUseCase.isRegistered()) {
                    return@launch
                }

                entityUpdates = integrationUseCase.getEntityUpdates()
                entityUpdates?.collect {
                     if (it.entityId == "light.philips_eyecare") {
                        Log.w(TAG, "Philips light changes! Update that fcking compl.")
                        val complicationDataSourceUpdateRequester =
                            ComplicationDataSourceUpdateRequester.create(
                                context = context,
                                complicationDataSourceComponent = thisDataSource
                            )
                        complicationDataSourceUpdateRequester.requestUpdate(complicationInstanceId)
                     }
                }            
            }
        }
    }

    /*
     * A request for representative preview data for the complication, for use in the editor UI.
     * Preview data is assumed to be static per type. E.g. for a complication that displays the
     * date and time of an event, rather than returning the real time it should return a fixed date
     * and time such as 10:10 Aug 1st.
     *
     * This will be called on a background thread.
     */
    override fun getPreviewData(type: ComplicationType): ComplicationData {
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text = "6!").build(),
            contentDescription = PlainComplicationText.Builder(text = "Short Text version of Number.").build()
        )
            .setTapAction(null)
            .build()
    }

    /*
     * Called when the complication needs updated data from your data source. There are four
     * scenarios when this will happen:
     *
     *   1. An active watch face complication is changed to use this data source
     *   2. A complication using this data source becomes active
     *   3. The period of time you specified in the manifest has elapsed (UPDATE_PERIOD_SECONDS)
     *   4. You triggered an update from your own class via the
     *       ComplicationDataSourceUpdateRequester.requestUpdate method.
     */
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Log.d(TAG, "onComplicationRequest() id: ${request.complicationInstanceId}")
     
        val thisDataSource = ComponentName(this, javaClass)
        // We pass the complication id, so we can only update the specific complication tapped.
        val complicationPendingIntent =
            ComplicationTapBroadcastReceiver.getToggleIntent(
                this,
                thisDataSource,
                request.complicationInstanceId
            )     
     
        val template = integrationUseCase.getTemplateTileContent()
        val renderedText = try {
            integrationUseCase.renderTemplate(template, mapOf())
        } catch (e: Exception) {
                "error"
        }

        return when (request.complicationType) {

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(text = renderedText).build(),
                contentDescription = PlainComplicationText
                    .Builder(text = "Rendered Template.").build()
            )
                .setTapAction(complicationPendingIntent)
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(text = renderedText).build(),
                contentDescription = PlainComplicationText
                    .Builder(text = "Rendered Template.").build()
            )
                .setTapAction(complicationPendingIntent)
                .build()
                
            else -> {
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "Unexpected complication type ${request.complicationType}")
                }
                null
            }
        }
    }
    /*
     * Called when the complication has been deactivated.
     */
    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        Log.d(TAG, "onComplicationDeactivated(): $complicationInstanceId")
        
        mainScope.cancel()
        entityUpdates = null
    }
    
    private fun updaterequest(complicationInstanceId: Int) {
        Log.w(TAG, "Update requested with a flow ${complicationInstanceId}")
    }
    companion object {
        private const val TAG = "CompDataSourceService"
    }
}
