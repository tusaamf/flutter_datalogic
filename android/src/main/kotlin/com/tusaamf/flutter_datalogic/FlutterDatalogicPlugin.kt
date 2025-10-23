package com.tusaamf.flutter_datalogic

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import com.datalogic.decode.BarcodeManager
import com.datalogic.decode.DecodeException
import com.datalogic.decode.configuration.KeyboardWedge
import com.datalogic.decode.configuration.LengthControlMode
import com.datalogic.decode.configuration.ScanMode
import com.datalogic.decode.configuration.ScannerProperties
import com.datalogic.device.configuration.BooleanProperty
import com.datalogic.device.configuration.ConfigException
import com.tusaamf.flutter_datalogic.const.MyChannels
import com.tusaamf.flutter_datalogic.const.ScannerStatus
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler


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

    private var scannedBarcode: String = ""
    private var scannedBarcodeId: String = ""

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext

        try {
            // Create a BarcodeManager. It will be used later to change intent delivery modes.
            manager = BarcodeManager().also {
                it.addReadListener{ result ->
                    scannedBarcode = result.text
                    scannedBarcodeId = result.barcodeID.name
                }
            }

            // get the current settings from the BarcodeManager
            configuration = ScannerProperties.edit(manager)

            configuration?.let {
//                it.keyboardWedge.enable.set(false)
                // set scan mode only 'single'
                // https://datalogic.github.io/oemconfig/scanner-settings/#scanner-options
                it.scannerOptions.scanMode.set(ScanMode.SINGLE)
                // set multi scan to false
                // https://datalogic.github.io/oemconfig/scanner-settings/#multi-scan
                it.multiScan.enable.set(false)
                // include the checksum in the label transmission
                // https://datalogic.github.io/oemconfig/scanner-settings/#code-39
                it.code39.enable.set(true)
                // https://datalogic.github.io/oemconfig/scanner-settings/#code-93
                it.code93.enable.set(true)
                // https://datalogic.github.io/oemconfig/scanner-settings/#code-128-gs1-128
                it.code128.enable.set(true)
                it.code128.gs1_128.set(true)
                // https://datalogic.github.io/oemconfig/scanner-settings/#msi
                it.msi.enable.set(true)
                it.msi.sendChecksum.set(true)
                // https://datalogic.github.io/oemconfig/scanner-settings/#upc-a
                it.upcA.enable.set(true)
                it.upcA.sendChecksum.set(true)
                it.upcA.convertToEan13.set(true)
                it.upcA.compositeEnable.set(true)
                // https://datalogic.github.io/oemconfig/scanner-settings/#upc-e
                it.upcE.enable.set(true)
                it.upcE.sendChecksum.set(true)
                it.upcE.compositeEnable.set(true)
                // https://datalogic.github.io/oemconfig/scanner-settings/#ean-8
                it.ean8.enable.set(true)
                it.ean8.sendChecksum.set(true)
                // https://datalogic.github.io/oemconfig/scanner-settings/#ean-13
                it.ean13.enable.set(true)
                it.ean13.sendChecksum.set(true)
                // https://datalogic.github.io/oemconfig/scanner-settings/#interleaved-2-of-5-itf-14
                it.interleaved25.enable.set(true)
                it.interleaved25.enableChecksum.set(true)
                it.interleaved25.sendChecksum.set(true)
                it.interleaved25.itf14.set(true)
                it.interleaved25.lengthMode.set(LengthControlMode.ONE_FIXED)
                it.interleaved25.Length1.set(14)
                it.interleaved25.Length2.set(14)
                // set default labelSuffix from [LF] to ""
                // https://datalogic.github.io/oemconfig/scanner-settings/#formatting
                it.format.labelSuffix.set("")
                // save settings
                it.store(manager, false)
            }
            listenScannerStatus()
        } catch (e: Exception) { // Any error?
            when (e) {
                is ConfigException -> Log.e(
                    LOG_TAG,
                    "Error while retrieving/setting properties: " + e.error_number,
                    e
                )

                is DecodeException -> Log.e(
                    LOG_TAG,
                    "Error while retrieving/setting properties: " + e.error_number,
                    e
                )

                else -> Log.e(LOG_TAG, "Other error ", e)
            }
            e.printStackTrace()
        }

        registerIntentBroadcastReceiver(flutterPluginBinding)

        configureMethodCallHandler(flutterPluginBinding)
    }

    private fun registerIntentBroadcastReceiver(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        try {
            // Register dynamically decode wedge intent broadcast receiver.
            intentFilter = IntentFilter().also {
                it.addAction("${context.packageName}${DLInterface.ACTION_SCANNER_INFO}")
            }

            scanEventChannel =
                EventChannel(flutterPluginBinding.binaryMessenger, MyChannels.scanChannel)
            scanEventChannel.setStreamHandler(this)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error while register intent broadcast receiver", e)
            e.printStackTrace()
        }
    }

    private fun configureMethodCallHandler(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        commandMethodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, MyChannels.commandChannel)
        commandMethodChannel.setMethodCallHandler(this)
    }
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startScanning" -> {
                manager?.pressTrigger()
                result.success(null)
            }

            "stopScanning" -> {
                manager?.releaseTrigger()
                result.success(null)
            }

            else -> {
                result.notImplemented()
            }
        }
    }

    private fun listenScannerStatus() {
        fun sendScannerInfo(status: ScannerStatus, barcode: String, barcodeId: String) {
            Intent().also { intent ->
                val bundle = Bundle().also {
                    it.putString(
                        DLInterface.EXTRA_KEY_VALUE_SCANNER_STATUS,
                        status.toString()
                    )
                    it.putString(
                        DLInterface.EXTRA_KEY_VALUE_SCAN_DATA,
                        barcode
                    )
                    it.putString(
                        DLInterface.EXTRA_KEY_VALUE_SCAN_DATA_ID,
                        barcodeId
                    )
                }

                intent.action = "${context.packageName}${DLInterface.ACTION_SCANNER_INFO}"
                intent.putExtra("${context.packageName}${DLInterface.EXTRA_SCANNER_INFO}", bundle)
                context.sendBroadcast(intent)
            }
        }
        manager?.addStartListener {
            scannedBarcode = ""
            sendScannerInfo(ScannerStatus.SCANNING, scannedBarcode, scannedBarcodeId)
        }
        manager?.addStopListener {
            sendScannerInfo(ScannerStatus.IDLE, scannedBarcode, scannedBarcodeId)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        for (receiver in registeredReceivers) {
            context.unregisterReceiver(receiver)
        }
        commandMethodChannel.setMethodCallHandler(null)
        scanEventChannel.setStreamHandler(null)
    }

@SuppressLint("UnspecifiedRegisterReceiverFlag")
override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    val receiver = SinkBroadcastReceiver(events)
    registeredReceivers.add(receiver)

    // برای اندروید 12 و بالاتر
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        context.registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED)
    } else {
        context.registerReceiver(receiver, intentFilter)
    }
}


    override fun onCancel(arguments: Any?) {
    }

    companion object {
        private const val LOG_TAG = "FlutterDatalogic"
    }
}
