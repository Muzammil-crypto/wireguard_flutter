package billion.group.wireguard_flutter


import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.PluginRegistry

import android.app.Activity
import io.flutter.embedding.android.FlutterActivity
import android.content.Intent
import android.content.Context
import android.util.Log
import com.beust.klaxon.Klaxon
import com.wireguard.android.backend.*
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.*
import java.util.*


import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/** WireguardFlutterPlugin */

const val PERMISSIONS_REQUEST_CODE = 10014
const val METHOD_CHANNEL_NAME = "billion.group.wireguard_flutter/wgcontrol"
const val METHOD_EVENT_NAME = "billion.group.wireguard_flutter/wgstage"

class WireguardFlutterPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.ActivityResultListener {
    private lateinit var channel: MethodChannel
    private lateinit var events: EventChannel
    private lateinit var tunnelName: String
    private val futureBackend = CompletableDeferred<Backend>()
    private var vpnStageSink: EventChannel.EventSink? = null
    private val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private var backend: Backend? = null
    private var havePermission = false
    private lateinit var context: Context
    private var activity: Activity? = null
    private var config: com.wireguard.config.Config? = null
    private var tunnel: WireguardTunnel? = null
    private val TAG = "NVPN"
    private var attached = true
    companion object {
        private var state: String = ""

        fun getStatus(): String {
            return state
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        this.havePermission =
            (requestCode == PERMISSIONS_REQUEST_CODE) && (resultCode == Activity.RESULT_OK)
        return havePermission
    }

    override fun onAttachedToActivity(activityPluginBinding: ActivityPluginBinding) {
        this.activity = activityPluginBinding.activity as FlutterActivity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        this.activity = null
    }

    override fun onReattachedToActivityForConfigChanges(activityPluginBinding: ActivityPluginBinding) {
        this.activity = activityPluginBinding.activity as FlutterActivity
    }

    override fun onDetachedFromActivity() {
        this.activity = null
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, METHOD_CHANNEL_NAME)
        events = EventChannel(flutterPluginBinding.binaryMessenger, METHOD_EVENT_NAME)
        context = flutterPluginBinding.applicationContext

        scope.launch(Dispatchers.IO) {
            try {
                backend = createBackend()
                futureBackend.complete(backend!!)
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }

        channel.setMethodCallHandler(this)
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                vpnStageSink = events
            }

            override fun onCancel(arguments: Any?) {
                vpnStageSink = null
            }
        })

    }

    private fun createBackend(): Backend {
        if (backend == null) {
            backend = GoBackend(context)
        }
        return backend as Backend
    }

    private fun flutterSuccess(result: Result, o: Any) {
        scope.launch(Dispatchers.Main) {
            result.success(o)
        }
    }

    private fun flutterError(result: Result, error: String) {
        scope.launch(Dispatchers.Main) {
            result.error(error, null, null)
        }
    }

    private fun flutterNotImplemented(result: Result) {
        scope.launch(Dispatchers.Main) {
            result.notImplemented()
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initialize" -> setupTunnel(call.argument<String>("localizedDescription").toString(), result)
            "start" -> {
                connect(call.argument<String>("wgQuickConfig").toString(), result)
            }
            "stop" -> {
                disconnect(result)
                updateStage("disconnected");
            }
            "getStats" -> handleGetStats(call.arguments, result)
            "stage" -> result.success(getStatus())
            "checkPermission" -> {
                checkPermission()
                result.success(null)
            }
            else -> flutterNotImplemented(result)
        }
    }

    private fun updateStage(stage: String?) {
        val updatedStage = stage ?: "disconnect"
        vpnStageSink?.success(updatedStage.lowercase(Locale.ROOT))
    }

    private fun handleGetStats(arguments: Any?, result: Result) {
        val tunnelName = arguments?.toString()
        if (tunnelName.isNullOrEmpty()) {
            flutterError(result, "Tunnel has not been initialized")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val statistics = futureBackend.await().getStatistics(tunnel(tunnelName))
                val stats = Stats(statistics.totalRx(), statistics.totalTx())

                flutterSuccess(
                    result, Klaxon().toJsonString(
                        stats
                    )
                )
                Log.i(TAG, "Statistics - ${stats.totalDownload} ${stats.totalUpload}")

            } catch (e: BackendException) {
                Log.e(TAG, "Statistics - BackendException - ERROR - ${e.reason} ", e)
                flutterError(result, e.reason.toString())
            } catch (e: Throwable) {
                Log.e(TAG, "Statistics - Can't get stats: ${e.message}", e)
                flutterError(result, e.message.toString())
            }
        }
    }

    private fun disconnect(result: Result) {
        setStage("disconnected")
        scope.launch(Dispatchers.IO) {
            try {
                if (futureBackend.await().runningTunnelNames.isEmpty()) {
                    throw Exception("Tunnel is not running")
                }
                futureBackend.await().setState(
                    tunnel(tunnelName) { state ->
                        scope.launch(Dispatchers.Main) {
                            Log.i(TAG, "onStateChange - $state")
                            channel.invokeMethod(
                                "onStateChange", state == Tunnel.State.DOWN
                            )
                            updateStage("disconnected")
                            setStage("disconnected")
                        }
                    }, Tunnel.State.DOWN, config
                )
                Log.i(TAG, "Disconnect - success!")
                flutterSuccess(result, "")
            } catch (e: BackendException) {
                Log.e(TAG, "Disconnect - BackendException - ERROR - ${e.reason}", e)
                flutterError(result, e.reason.toString())
            } catch (e: Throwable) {
                Log.e(TAG, "Disconnect - Can't disconnect from tunnel: ${e.message}")
                flutterError(result, e.message.toString())
            }
        }
    }

    private fun connect(wgQuickConfig: String, result: Result) {
        setStage("connecting")
        scope.launch(Dispatchers.IO) {
            try {
                if (!havePermission) {
                    checkPermission()
                    throw Exception("Permissions are not given")
                }
                val inputStream = ByteArrayInputStream(wgQuickConfig.toByteArray())
                config = com.wireguard.config.Config.parse(inputStream)
                futureBackend.await().setState(
                    tunnel(tunnelName) { state ->
                        scope.launch(Dispatchers.Main) {
                            Log.i(TAG, "onStateChange - $state")
                            updateStage("connected")
                            channel.invokeMethod(
                                "onStateChange", state == Tunnel.State.UP
                            )
                            updateStage("connected")
                        }
                    }, Tunnel.State.UP, config
                )
                Log.i(TAG, "Connect - success!")
                flutterSuccess(result, "")
            } catch (e: BackendException) {
                Log.e(TAG, "Connect - BackendException - ERROR - ${e.reason}", e)
                flutterError(result, e.reason.toString())
            } catch (e: Throwable) {
                Log.e(TAG, "Connect - Can't connect to tunnel: $e", e)
                flutterError(result, e.message.toString())
            }
        }
    }

    private fun setupTunnel(localizedDescription: String, result: Result) {
        scope.launch(Dispatchers.IO) {
            if (Tunnel.isNameInvalid(localizedDescription)) {
                flutterError(result, "Invalid Name")
                return@launch
            }
            tunnelName = localizedDescription
            checkPermission()
            result.success(null)
        }
    }

    private fun checkPermission() {
        val intent = GoBackend.VpnService.prepare(this.activity)
        if (intent != null) {
            havePermission = false
            this.activity?.startActivityForResult(intent, PERMISSIONS_REQUEST_CODE)
        } else {
            havePermission = true
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null)
    }

    private fun setStage(stage: String) {
        when (stage) {
            "UP" -> if (vpnStageSink != null && attached) vpnStageSink?.success("connected")
            "DOWN" -> if (vpnStageSink != null && attached) vpnStageSink?.success("disconnected")
            "TOGGLE" -> if (vpnStageSink != null && attached) vpnStageSink?.success("authenticating")
            else -> if (vpnStageSink != null && attached) vpnStageSink?.success("none")
        }
    }

    private fun tunnel(name: String, callback: StateChangeCallback? = null): WireguardTunnel {
        if (tunnel == null) {
            tunnel = WireguardTunnel(name, callback)
        }
        return tunnel as WireguardTunnel
    }
}

typealias StateChangeCallback = (Tunnel.State) -> Unit

class WireguardTunnel(
    private val name: String, private val onStateChanged: StateChangeCallback? = null
) : Tunnel {

    override fun getName() = name

    override fun onStateChange(newState: Tunnel.State) {
        onStateChanged?.invoke(newState)
    }

}

class Stats(
    val totalDownload: Long,
    val totalUpload: Long,
)
