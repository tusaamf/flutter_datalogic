package com.tusaamf.flutter_datalogic

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import com.tusaamf.flutter_datalogic.const.MyChannels

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.lang.Exception
import com.datalogic.decode.BarcodeManager
import com.datalogic.decode.DecodeException
import com.datalogic.decode.StartListener
import com.datalogic.decode.StopListener
import com.datalogic.decode.configuration.IntentDeliveryMode
import com.datalogic.decode.configuration.ScannerProperties
import com.datalogic.device.configuration.ConfigException
import com.tusaamf.flutter_datalogic.const.MyMethods
import com.tusaamf.flutter_datalogic.const.ScannerStatus
import io.flutter.plugin.common.MethodChannel

/** FlutterDatalogicPlugin */
class FlutterDatalogicPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {

    private lateinit var scanEventChannel: EventChannel

    private lateinit var commandMethodChannel: MethodChannel

    private var manager: BarcodeManager? = null

    private var configuration: ScannerProperties? = null

    /**
     * Used to save BroadcastReceiver to be able unregister them.
     */
    private val registeredReceivers: ArrayList<SinkBroadcastReceiver> = ArrayList()

    private lateinit var context: Context

    private lateinit var intentFilter: IntentFilter

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext

        try {
            // Create a BarcodeManager. It will be used later to change intent delivery modes.
            manager = BarcodeManager()

            // get the current settings from the BarcodeManager
            configuration = ScannerProperties.edit(manager)

            configuration?.let {
                it.ean13.sendChecksum.set(true)
                // disables KeyboardWedge
                it.keyboardWedge.enable.set(false)
                // enable wedge intent
                it.intentWedge.enable.set(true)
                // set wedge intent action and category
                it.intentWedge.action.set(DLInterface.ACTION_BROADCAST_RECEIVER)
                it.intentWedge.category.set(DLInterface.CATEGORY_BROADCAST_RECEIVER)
                // set wedge intent delivery through broadcast
                it.intentWedge.deliveryMode.set(IntentDeliveryMode.BROADCAST)
                it.store(manager, false)
            }
        } catch (e: Exception) { // Any error?
            when (e) {
                is ConfigException -> Log.e(LOG_TAG, "Error while retrieving/setting properties: " + e.error_number, e)
                is DecodeException -> Log.e(LOG_TAG, "Error while retrieving/setting properties: " + e.error_number, e)
                else -> Log.e(LOG_TAG, "Other error ", e)
            }
            e.printStackTrace()
        }

        try {
            // Register dynamically decode wedge intent broadcast receiver.
            intentFilter = IntentFilter()
            intentFilter.addAction(DLInterface.ACTION_SCANNER_STATUS)
            intentFilter.addAction(DLInterface.ACTION_BROADCAST_RECEIVER)
            intentFilter.addCategory(DLInterface.CATEGORY_BROADCAST_RECEIVER)

            scanEventChannel =
                EventChannel(flutterPluginBinding.binaryMessenger, MyChannels.scanChannel)
            scanEventChannel.setStreamHandler(this)

            commandMethodChannel =
                MethodChannel(flutterPluginBinding.binaryMessenger, MyChannels.commandChannel)
            commandMethodChannel.setMethodCallHandler(this)

        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error while register intent broadcast receiver", e)
            e.printStackTrace()
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            MyMethods.listenScannerStatus -> {
                listenScannerStatus()
                result.success(null)  //  Datalogic does not return responses
            }

            else -> {
                result.notImplemented()
            }
        }
    }

    private fun listenScannerStatus() {
        val startListener = StartListener {
            Intent().also { intent ->
                val bundle = Bundle().also {
                  it.putString(DLInterface.EXTRA_KEY_VALUE_SCANNER_STATUS, ScannerStatus.SCANNING.toString())
                }

                intent.action = DLInterface.ACTION_SCANNER_STATUS
                intent.putExtra(DLInterface.EXTRA_SCANNER_STATUS, bundle)
                context.sendBroadcast(intent)
            }
        }
        val stopListener = StopListener {
            Intent().also { intent ->
                val bundle = Bundle().also {
                    it.putString(DLInterface.EXTRA_KEY_VALUE_SCANNER_STATUS, ScannerStatus.IDLE.toString())
                }

                intent.action = DLInterface.ACTION_SCANNER_STATUS
                intent.putExtra(DLInterface.EXTRA_SCANNER_STATUS, bundle)
                context.sendBroadcast(intent)
            }
        }
        manager?.addStartListener(startListener)
        manager?.addStopListener(stopListener)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        for (receiver in registeredReceivers) {
            context.unregisterReceiver(receiver)
        }
        scanEventChannel.setStreamHandler(null)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        val receiver = SinkBroadcastReceiver(events)
        registeredReceivers.add(receiver)
        context.registerReceiver(receiver, intentFilter)
    }

    override fun onCancel(arguments: Any?) {
    }

    companion object {
        private const val LOG_TAG = "FlutterDatalogic"
    }
}
