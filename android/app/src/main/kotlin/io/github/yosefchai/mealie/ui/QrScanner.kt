package io.github.yosefchai.mealie.ui

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * סריקת QR עם Google code scanner: בלי הרשאת מצלמה, ובלי לקרוס בשום מצב.
 * - אין Google Play services (למשל מכשיר בלי שירותי Google): הודעה, והמשתמש מקליד ידנית.
 * - מודול הסורק עוד לא הורד: מבקשים הורדה ברקע (ModuleInstall) ומבקשים לנסות שוב.
 * - ביטול על ידי המשתמש: שקט.
 */
object QrScanner {
    sealed interface Outcome {
        data class Scanned(val value: String) : Outcome
        data object Cancelled : Outcome
        data object NoPlayServices : Outcome
        data object Preparing : Outcome
        data object Failed : Outcome
    }

    private const val TAG = "QrScanner"

    fun scan(context: Context, onResult: (Outcome) -> Unit) {
        val available = runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) }
            .getOrDefault(ConnectionResult.SERVICE_MISSING)
        if (available != ConnectionResult.SUCCESS) {
            onResult(Outcome.NoPlayServices)
            return
        }
        val scanner = try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            GmsBarcodeScanning.getClient(context, options)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Code scanner client failed", e)
            onResult(Outcome.Failed)
            return
        }
        val modules = ModuleInstall.getClient(context)
        modules.areModulesAvailable(scanner)
            .addOnSuccessListener { response ->
                if (response.areModulesAvailable()) {
                    start(scanner, modules, onResult)
                } else {
                    requestInstall(modules, scanner)
                    onResult(Outcome.Preparing)
                }
            }
            // אם הבדיקה עצמה נכשלה, מנסים לסרוק בכל זאת; כשל אמיתי ייתפס שם
            .addOnFailureListener { start(scanner, modules, onResult) }
    }

    private fun start(scanner: GmsBarcodeScanner, modules: ModuleInstallClient, onResult: (Outcome) -> Unit) {
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue?.trim()
                onResult(if (value.isNullOrEmpty()) Outcome.Failed else Outcome.Scanned(value))
            }
            .addOnCanceledListener { onResult(Outcome.Cancelled) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Scan failed", e)
                if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) {
                    requestInstall(modules, scanner)
                    onResult(Outcome.Preparing)
                } else {
                    onResult(Outcome.Failed)
                }
            }
    }

    private fun requestInstall(modules: ModuleInstallClient, scanner: GmsBarcodeScanner) {
        modules.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
            .addOnFailureListener { Log.w(TAG, "Scanner module install failed", it) }
    }
}
