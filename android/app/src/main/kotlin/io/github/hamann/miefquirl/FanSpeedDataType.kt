package io.github.hamann.miefquirl

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import io.github.hamann.miefquirl.headwind.Fan
import io.github.hamann.miefquirl.headwind.FanAction
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The fan as a data field: shows current output, and tries to accept taps.
 *
 * The Karoo SDK has no input API for data fields — [io.hammerhead.karooext.models.ViewEvent]
 * only flows this way. The one avenue available is that [ViewEmitter.updateView]
 * takes [RemoteViews], which the Karoo inflates in its own process, and
 * RemoteViews carry PendingIntents. Whether a tap actually reaches the buttons
 * depends entirely on whether the data page forwards touches or consumes them
 * for paging — undocumented, and the reason this is worth testing on-device.
 *
 * If taps turn out not to work the field still renders the value correctly, so
 * this degrades to the read-only display it would otherwise have been.
 */
class FanSpeedDataType(
    private val context: Context,
    extension: String,
    private val fan: StateFlow<Fan>,
) : DataTypeImpl(extension, TYPE_ID) {

    /**
     * Keeps the field "live" without handing the Karoo a value to draw.
     *
     * The Karoo renders its own large numeric treatment whenever
     * [DataType.Field.SINGLE] is present in the data point — which collided
     * with the buttons and showed the value twice. Publishing under a private
     * field name keeps the stream flowing while leaving the whole field to
     * [startView].
     */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            fan.map { it.output }
                .distinctUntilChanged()
                .collect { output ->
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(
                                dataTypeId = dataTypeId,
                                values = mapOf(FIELD_PERCENT to output.toDouble()),
                            ),
                        ),
                    )
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Timber.i("startView: size=%s align=%s", config.viewSize, config.alignment)

        val job = CoroutineScope(Dispatchers.IO).launch {
            fan.map { it.label }
                .distinctUntilChanged()
                .collect { label ->
                    emitter.updateView(render(label, config))
                    // ViewEmitter silently drops updates arriving under ~900ms
                    // apart. The flow is conflated, so pacing here costs
                    // nothing but keeps every update from being thrown away.
                    delay(VIEW_INTERVAL_MS)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun render(label: String, config: ViewConfig): RemoteViews =
        RemoteViews(context.packageName, R.layout.fan_field).apply {
            setTextViewText(R.id.fan_value, label)
            // config.textSize is what the Karoo would use for this grid size if
            // it were drawing the number itself, so the field matches the rest
            // of the page. Scaled down because the buttons take the lower third
            // and the standard treatment assumes the whole field.
            setTextViewTextSize(
                R.id.fan_value,
                TypedValue.COMPLEX_UNIT_SP,
                config.textSize * VALUE_TEXT_SCALE,
            )
            setOnClickPendingIntent(R.id.fan_value, pendingIntent(FanAction.TOGGLE))
            setOnClickPendingIntent(R.id.fan_down, pendingIntent(FanAction.DOWN))
            setOnClickPendingIntent(R.id.fan_up, pendingIntent(FanAction.UP))
        }

    private fun pendingIntent(action: FanAction): PendingIntent {
        val intent = Intent(context, FanActionReceiver::class.java)
            .setAction(FanActionReceiver.ACTION)
            .putExtra(FanActionReceiver.EXTRA_ACTION, action.id)
        return PendingIntent.getBroadcast(
            context,
            // Distinct request codes, or the two buttons would share one
            // PendingIntent and both would send whichever was created last.
            action.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val TYPE_ID = "fan-speed"

        /** Deliberately not [DataType.Field.SINGLE] — see [startStream]. */
        private const val FIELD_PERCENT = "FAN_PERCENT"

        private const val VIEW_INTERVAL_MS = 1_000L

        /** Fraction of the Karoo's standard numeric size; the buttons take the rest. */
        private const val VALUE_TEXT_SCALE = 0.75f
    }
}
