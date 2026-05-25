package ${{packageId}}

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.facebook.react.PackageList
import com.facebook.react.ReactHost
import com.facebook.react.ReactPackage
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.bridge.ReactContext
import com.facebook.react.common.ReleaseLevel
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint
import com.facebook.react.modules.core.DeviceEventManagerModule
import expo.modules.ExpoReactHostFactory
import expo.modules.brownfield.BrownfieldNavigationState

class ReactNativeHostManager {
  companion object {
    val shared: ReactNativeHostManager by lazy { ReactNativeHostManager() }
    private var reactHost: ReactHost? = null
  }

  fun getReactHost(): ReactHost? {
    return reactHost
  }

  fun initialize(application: Application, additionalPackages: List<ReactPackage> = emptyList()) {
    if (reactHost != null) {
      return
    }

    // Ensure that `index.android.bundle` is available in the assets
    // for release builds
    if (!BuildConfig.DEBUG) {
      val assets = application.applicationContext.assets.list("")?.toList()
        ?: emptyList<String>()
      if (!assets.contains("index.android.bundle")) {
        val bundleList = assets
          .filter { it.endsWith(".bundle") }
          .map { "- $it" }.joinToString("\n")
          ?: "None"

          throw IllegalStateException("""
          Cannot find `index.android.bundle` in the assets
          Available JS bundles:
          $bundleList
          """.trimIndent()
        )
      }
    }

    DefaultNewArchitectureEntryPoint.releaseLevel =
        try {
          ReleaseLevel.valueOf(BuildConfig.REACT_NATIVE_RELEASE_LEVEL.uppercase())
        } catch (e: IllegalArgumentException) {
          ReleaseLevel.STABLE
        }
    loadReactNative(application)
    BrownfieldLifecycleDispatcher.onApplicationCreate(application)

    // Use the BROWNFIELD's own `BuildConfig.DEBUG` (not the default `ReactBuildConfig.DEBUG`).
    // In fused mode the published Module Metadata forces consumers to resolve
    // `com.facebook.react:react-android`'s RELEASE variant (required so the brownfield's
    // codegen `.so` ABI lines up with the runtime `libreactnative.so` — see the
    // `BuildTypeAttr=release` injection in `fused/build.gradle.kts`). That release
    // variant has `ReactBuildConfig.DEBUG=false`, so if we let it pick the default
    // we'd never connect to Metro even on a debug brownfield. Reading our own
    // `BuildConfig.DEBUG` — true in `-fused-debug`, false in `-fused-release` —
    // gives the right behavior in both.
    val resolvedPackages = PackageList(application).packages + additionalPackages
    Log.d(
      "BROWNFIELD-DEBUG",
      "initialize: useDevSupport=${BuildConfig.DEBUG}, packageList(${resolvedPackages.size})=" +
        resolvedPackages.joinToString(", ") { it.javaClass.name }
    )
    reactHost = ExpoReactHostFactory.getDefaultReactHost(
      context = application.applicationContext,
      packageList = resolvedPackages,
      useDevSupport = BuildConfig.DEBUG
    )
    // Fires when ReactInstance construction finishes (after RN auto-adds
    // `CoreReactPackage`). Log whether `PlatformConstants` is resolvable then —
    // tells us if the TurboModule registry is populated correctly or whether
    // CoreReactPackage's `getModule("PlatformConstants")` is silently returning null.
    reactHost?.addReactInstanceEventListener(object : ReactInstanceEventListener {
      override fun onReactContextInitialized(context: ReactContext) {
        val catalyst = context.catalystInstance
        val pcModule = runCatching { catalyst?.getNativeModule("PlatformConstants") }.getOrNull()
        val allModules = runCatching {
          catalyst?.nativeModules?.map { it.name }?.sorted()
        }.getOrNull()
        Log.d(
          "BROWNFIELD-DEBUG",
          "ReactContext initialized. PlatformConstants lookup=" +
            "${pcModule?.javaClass?.name ?: "<null>"}, " +
            "total registered modules=${allModules?.size ?: -1}"
        )
        // Print the first ~30 module names so we can see if PlatformConstants is
        // there at all — Log.d truncates long lines, so split.
        allModules?.chunked(15)?.forEachIndexed { i, chunk ->
          Log.d("BROWNFIELD-DEBUG", "modules[$i]: ${chunk.joinToString(", ")}")
        }
      }
    })
  }
}

fun Activity.showReactNativeFragment(rootComponent: String = "main", additionalPackages: List<ReactPackage> = emptyList()) {
  ReactNativeHostManager.shared.initialize(this.application, additionalPackages)
  val fragment = ReactNativeFragment.createFragmentHost(this, rootComponent)
  setContentView(fragment)
  setUpNativeBackHandling()
}

fun Activity.setUpNativeBackHandling() {
  val componentActivity = this as? ComponentActivity
  if (componentActivity == null) {
    return
  }

  val backCallback =
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (BrownfieldNavigationState.nativeBackEnabled) {
            isEnabled = false
            componentActivity.onBackPressedDispatcher?.onBackPressed()
            isEnabled = true
          } else {
            val reactHost = ReactNativeHostManager.shared.getReactHost()
            reactHost?.currentReactContext?.let { reactContext ->
              val deviceEventManager =
                  reactContext.getNativeModule(DeviceEventManagerModule::class.java)
              deviceEventManager?.emitHardwareBackPressed()
            }
          }
        }
      }

  componentActivity.onBackPressedDispatcher?.addCallback(componentActivity, backCallback)
}
