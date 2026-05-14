package com.example.elm327

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.elm327.adapter.MessageAdapter
import com.example.elm327.databinding.ActivityMainBinding
import com.example.elm327.viewmodel.ConnectionState
import com.example.elm327.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var bluetoothAdapter: BluetoothAdapter

    // ─────────────────────────────────────────────────────────────────────────
    // Activity Result Launchers
    // ─────────────────────────────────────────────────────────────────────────

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            checkBluetoothAndConnect()
        } else {
            Toast.makeText(this, getString(R.string.err_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            showDevicePicker()
        } else {
            Toast.makeText(this, getString(R.string.err_bt_must_be_enabled), Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val btManager = getSystemService(BluetoothManager::class.java)
        val adapter   = btManager?.adapter
        if (adapter == null) {
            Toast.makeText(this, getString(R.string.err_bluetooth_not_supported), Toast.LENGTH_LONG).show()
            binding.btnConnect.isEnabled = false
            return
        }
        bluetoothAdapter = adapter

        setupRecyclerView()
        setupObservers()
        setupClickListeners()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter()
        binding.rvMessages.apply {
            adapter       = messageAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).also {
                it.stackFromEnd = true
            }
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionUI(state)
                    }
                }
                launch {
                    viewModel.messages.collect { messages ->
                        messageAdapter.submitList(messages.toList())
                        if (messages.isNotEmpty()) {
                            binding.rvMessages.smoothScrollToPosition(messages.size - 1)
                        }
                    }
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnConnect.setOnClickListener {
            when (viewModel.connectionState.value) {
                is ConnectionState.Connected -> viewModel.disconnect()
                is ConnectionState.Connecting -> { /* aguardar */ }
                else -> checkPermissionsAndConnect()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI de conexão
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateConnectionUI(state: ConnectionState) {
        when (state) {
            is ConnectionState.Disconnected -> {
                binding.tvStatus.text = getString(R.string.status_disconnected)
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                binding.tvDeviceInfo.visibility = View.GONE
                binding.btnConnect.isEnabled    = true
                binding.btnConnect.text         = getString(R.string.btn_connect)
            }
            is ConnectionState.Connecting -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                binding.btnConnect.isEnabled = false
            }
            is ConnectionState.Connected -> {
                binding.tvStatus.text = getString(R.string.status_connected)
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                binding.tvDeviceInfo.text       = "${state.deviceName}  |  ${state.macAddress}"
                binding.tvDeviceInfo.visibility = View.VISIBLE
                binding.btnConnect.isEnabled    = true
                binding.btnConnect.text         = getString(R.string.btn_disconnect)
            }
            is ConnectionState.Error -> {
                binding.tvStatus.text = "Erro: ${state.message}"
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                binding.tvDeviceInfo.visibility = View.GONE
                binding.btnConnect.isEnabled    = true
                binding.btnConnect.text         = getString(R.string.btn_connect)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissões e Bluetooth
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkPermissionsAndConnect() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            checkBluetoothAndConnect()
        } else {
            requestPermissionsLauncher.launch(permissions)
        }
    }

    private fun checkBluetoothAndConnect() {
        if (!bluetoothAdapter.isEnabled) {
            @Suppress("MissingPermission")
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            showDevicePicker()
        }
    }

    private fun showDevicePicker() {
        @Suppress("MissingPermission")
        val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices ?: emptySet()

        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, getString(R.string.err_no_paired_devices), Toast.LENGTH_LONG).show()
            return
        }

        val deviceList  = pairedDevices.toList()
        @Suppress("MissingPermission")
        val deviceNames = deviceList.map { device ->
            "${device.name ?: "Desconhecido"}\n${device.address}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_select_device))
            .setItems(deviceNames) { _, index ->
                // Passa também o bluetoothAdapter para que o manager possa
                // chamar cancelDiscovery() antes de conectar
                viewModel.connect(deviceList[index], bluetoothAdapter)
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }
}
