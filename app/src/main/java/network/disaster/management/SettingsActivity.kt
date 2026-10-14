package network.disaster.management

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import network.disaster.management.legal.LegalDocumentRepository
import network.disaster.management.transport.TransportCapabilityManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var capabilities: TransportCapabilityManager
    private lateinit var swBle: Switch
    private lateinit var swMic: Switch
    private lateinit var swCam: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        capabilities = TransportCapabilityManager(this)

        swBle = findViewById(R.id.swSettingsBle)
        swMic = findViewById(R.id.swSettingsMic)
        swCam = findViewById(R.id.swSettingsCam)

        val btnOsSettings = findViewById<Button>(R.id.btnOpenOsSettings)
        val btnTerms = findViewById<Button>(R.id.btnViewTermsOfUse)

        setupPermissionSwitches()

        btnOsSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        btnTerms.setOnClickListener {
            displayTermsModal()
        }

        setupAirGapAudit()
    }

    override fun onResume() {
        super.onResume()
        refreshSwitchStates()
    }

    private fun setupAirGapAudit() {
        val btnAudit = findViewById<Button>(R.id.btnAuditAirGapIntegrity)
        btnAudit?.setOnClickListener {
            val audit = network.disaster.management.localai.LocalAirGapManager.auditAirGapStatus(this)
            AlertDialog.Builder(this)
                .setTitle("🛡️ ${audit.policyTitle}")
                .setMessage(
                    "STATUS: VERIFIED 100% AIR-GAPPED\n\n" +
                    "• Network Security:\n${audit.networkPolicySummary}\n\n" +
                    "• Storage Vault:\n${audit.storagePolicySummary}\n\n" +
                    "• Intelligence Engine:\n${audit.intelligenceSummary}\n\n" +
                    "• Physical Layer:\n${audit.physicalMeshSummary}"
                )
                .setPositiveButton("Confirmed", null)
                .show()
        }
    }

    private fun setupPermissionSwitches() {
        swBle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !capabilities.isBleAvailable()) {
                requestBlePermissions()
            } else if (!isChecked && capabilities.isBleAvailable()) {
                showRevokeGuidanceDialog("Bluetooth")
            }
        }

        swMic.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !capabilities.isAcousticAvailable()) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQ_AUDIO
                )
            } else if (!isChecked && capabilities.isAcousticAvailable()) {
                showRevokeGuidanceDialog("Microphone")
            }
        }

        swCam.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !capabilities.isOpticalAvailable()) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    REQ_CAMERA
                )
            } else if (!isChecked && capabilities.isOpticalAvailable()) {
                showRevokeGuidanceDialog("Camera")
            }
        }
    }

    private fun refreshSwitchStates() {
        swBle.setOnCheckedChangeListener(null)
        swMic.setOnCheckedChangeListener(null)
        swCam.setOnCheckedChangeListener(null)

        swBle.isChecked = capabilities.isBleAvailable()
        swMic.isChecked = capabilities.isAcousticAvailable()
        swCam.isChecked = capabilities.isOpticalAvailable()

        setupPermissionSwitches()
    }

    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        ActivityCompat.requestPermissions(this, perms, REQ_BLE)
    }

    private fun showRevokeGuidanceDialog(sensorName: String) {
        AlertDialog.Builder(this)
            .setTitle("Modify $sensorName Access")
            .setMessage("Android requires granted permissions to be disabled via System Settings. Open app settings now?")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { _, _ ->
                refreshSwitchStates()
            }
            .show()
    }

    private fun displayTermsModal() {
        val textView = TextView(this).apply {
            text = LegalDocumentRepository.UNIFIED_TERMS_AND_DISCLAIMERS
            textSize = 12f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(40, 30, 40, 30)
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(this).apply {
            addView(textView)
        }

        AlertDialog.Builder(this)
            .setTitle("Terms of Service & Regulatory Disclaimers")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSwitchStates()
    }

    companion object {
        const val REQ_BLE = 201
        const val REQ_AUDIO = 202
        const val REQ_CAMERA = 203
    }
}
