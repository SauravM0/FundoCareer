package com.fundocareer.app.core.jobalerts

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.fundocareer.app.core.logging.FcLog

object OEMDeviceHelper {

    enum class Manufacturer {
        XIAOMI,
        OPPO,
        REALME,
        VIVO,
        ONEPLUS,
        SAMSUNG,
        HUAWEI,
        HONOR,
        OTHER
    }

    fun getManufacturer(): Manufacturer {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            brand.contains("xiaomi") || manufacturer.contains("xiaomi") -> Manufacturer.XIAOMI
            brand.contains("oppo") || manufacturer.contains("oppo") -> Manufacturer.OPPO
            brand.contains("realme") || manufacturer.contains("realme") -> Manufacturer.REALME
            brand.contains("vivo") || manufacturer.contains("vivo") -> Manufacturer.VIVO
            brand.contains("oneplus") || manufacturer.contains("oneplus") -> Manufacturer.ONEPLUS
            brand.contains("samsung") || manufacturer.contains("samsung") -> Manufacturer.SAMSUNG
            brand.contains("huawei") || manufacturer.contains("huawei") -> Manufacturer.HUAWEI
            brand.contains("honor") || manufacturer.contains("honor") -> Manufacturer.HONOR
            else -> Manufacturer.OTHER
        }
    }

    fun getBrandName(): String {
        return when (getManufacturer()) {
            Manufacturer.XIAOMI -> "Xiaomi"
            Manufacturer.OPPO -> "Oppo"
            Manufacturer.REALME -> "Realme"
            Manufacturer.VIVO -> "Vivo"
            Manufacturer.ONEPLUS -> "OnePlus"
            Manufacturer.SAMSUNG -> "Samsung"
            Manufacturer.HUAWEI -> "Huawei"
            Manufacturer.HONOR -> "Honor"
            Manufacturer.OTHER -> Build.MANUFACTURER
        }
    }

    fun hasOemAutostart(): Boolean {
        return getManufacturer() != Manufacturer.OTHER
    }

    fun getAutostartIntent(context: Context): Intent? {
        val pkg = context.packageName
        return when (getManufacturer()) {
            Manufacturer.XIAOMI -> {
                try {
                    Intent("miui.intent.action.OP_AUTO_START").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        `package` = "com.miui.securitycenter"
                    }
                } catch (e: Exception) {
                    FcLog.w(FcLog.TAG_RELIABILITY, "Xiaomi autostart intent failed", mapOf(
                        "error" to e.message,
                    ))
                    appDetailsIntent(pkg)
                }
            }
            Manufacturer.OPPO, Manufacturer.REALME -> {
                try {
                    Intent("oppo.intent.action.OP_AUTO_START").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        `package` = "com.coloros.safecenter"
                    }
                } catch (e: Exception) {
                    FcLog.w(FcLog.TAG_RELIABILITY, "Oppo/Realme autostart intent failed", mapOf(
                        "error" to e.message,
                    ))
                    appDetailsIntent(pkg)
                }
            }
            Manufacturer.VIVO -> {
                try {
                    Intent("vivo.intent.action.OP_AUTO_START").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } catch (e: Exception) {
                    FcLog.w(FcLog.TAG_RELIABILITY, "Vivo autostart intent failed", mapOf(
                        "error" to e.message,
                    ))
                    appDetailsIntent(pkg)
                }
            }
            else -> appDetailsIntent(pkg)
        }
    }

    fun getBatterySettingsIntent(context: Context): Intent? {
        val pkg = context.packageName
        return when (getManufacturer()) {
            Manufacturer.ONEPLUS -> {
                try {
                    Intent("oneplus.intent.action.OP_BACKGROUND_OPTIMIZATION").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        `package` = "com.oneplus.dos"
                    }
                } catch (e: Exception) {
                    FcLog.w(FcLog.TAG_RELIABILITY, "OnePlus battery intent failed", mapOf(
                        "error" to e.message,
                    ))
                    appDetailsIntent(pkg)
                }
            }
            else -> null
        }
    }

    fun getDeviceSpecificAutostartInstructions(): String {
        return when (getManufacturer()) {
            Manufacturer.XIAOMI ->
                "Open Settings > Apps > Manage apps > FundoCareer > enable Autostart"
            Manufacturer.OPPO, Manufacturer.REALME ->
                "Open Settings > App Management > FundoCareer > enable Allow auto-launch"
            Manufacturer.VIVO ->
                "Open Settings > More settings > Applications > Autostart > enable FundoCareer"
            Manufacturer.ONEPLUS ->
                "Open Settings > Apps > FundoCareer > Battery > enable Allow background activity"
            Manufacturer.SAMSUNG ->
                "Open Settings > Apps > FundoCareer > Battery > enable Allow background activity"
            Manufacturer.HUAWEI ->
                "Open Settings > Apps > FundoCareer > App launch > enable Allow auto-launch"
            Manufacturer.HONOR ->
                "Open Settings > Apps > FundoCareer > App launch > enable Allow auto-launch"
            Manufacturer.OTHER ->
                "Open Settings > Apps > FundoCareer and enable any autostart or background activity options"
        }
    }

    fun getBatteryInstructions(): String {
        return when (getManufacturer()) {
            Manufacturer.XIAOMI ->
                "Settings > Apps > Manage apps > FundoCareer > Battery saver > No restrictions"
            Manufacturer.OPPO, Manufacturer.REALME ->
                "Settings > App Management > FundoCareer > Power saving > Allow background activity"
            Manufacturer.VIVO ->
                "Settings > Battery > App battery management > FundoCareer > Allow background"
            Manufacturer.ONEPLUS ->
                "Settings > Battery > App quick optimization > FundoCareer > Don't optimize"
            Manufacturer.SAMSUNG ->
                "Settings > Apps > FundoCareer > Battery > Allow background activity"
            Manufacturer.HUAWEI ->
                "Settings > Apps > FundoCareer > App launch > Manage manually > Enable all"
            Manufacturer.HONOR ->
                "Settings > Apps > FundoCareer > App launch > Enable all"
            Manufacturer.OTHER ->
                "Open Settings > Apps > FundoCareer > Battery and set to Unrestricted"
        }
    }

    private fun appDetailsIntent(pkg: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
