package com.tck.factoryflow.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tck.factoryflow.auth.FactoryAuthRepository
import com.tck.factoryflow.auth.FactorySession
import com.tck.factoryflow.auth.PasswordSecurity
import com.tck.factoryflow.data.*
import com.tck.factoryflow.sync.CloudInventoryRepository
import com.tck.factoryflow.sync.CloudStockSnapshot
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class FactoryViewModel(application: Application) : AndroidViewModel(application) {
    private val db = FactoryDatabase.get(application)
    val dao: FactoryDao = db.factoryDao()
    private val authRepo = FactoryAuthRepository(application)
    private val cloudRepo = CloudInventoryRepository(application)

    private val _session = MutableStateFlow<FactorySession?>(null)
    val session: StateFlow<FactorySession?> = _session.asStateFlow()

    private val _isCheckingSession = MutableStateFlow(true)
    val isCheckingSession: StateFlow<Boolean> = _isCheckingSession.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _cloudStockSnapshot = MutableStateFlow<CloudStockSnapshot?>(null)
    val cloudStockSnapshot: StateFlow<CloudStockSnapshot?> = _cloudStockSnapshot.asStateFlow()

    private val _cloudStockLoading = MutableStateFlow(false)
    val cloudStockLoading: StateFlow<Boolean> = _cloudStockLoading.asStateFlow()

    private val _cloudStockError = MutableStateFlow<String?>(null)
    val cloudStockError: StateFlow<String?> = _cloudStockError.asStateFlow()

    val products = dao.observeProducts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val staffMembers = dao.observeStaffMembers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val productionLines = dao.observeProductionLines().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val production = dao.observeProduction().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val packing = dao.observePacking().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val dispatches = dao.observeDispatches().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val dispatchBatches = dao.observeDispatchBatches().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val dispatchLines = dao.observeDispatchLines().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val meters = dao.observeMeterReadings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val customers = dao.observeCustomers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val machines = dao.observeMachines().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val machineEvents = dao.observeMachineEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val qualityInspections = dao.observeQualityInspections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val plans = dao.observeProductionPlans().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val orders = dao.observeSalesOrders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val openOrders = dao.observeOpenSalesOrders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val orderLines = dao.observeSalesOrderLines().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val inventoryMovements = dao.observeRecentInventoryMovements().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val auditEvents = dao.observeAuditEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val syncEvents = dao.observeSyncOutbox().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _session.value = runCatching { authRepo.currentSession() }.getOrNull()
            _isCheckingSession.value = false
            PasswordSecurity.initializeDefaultAdminIfNeeded(getApplication())
        }
    }

    fun showMessage(msg: String) {
        _message.value = msg
        viewModelScope.launch {
            kotlinx.coroutines.delay(2600)
            if (_message.value == msg) _message.value = null
        }
    }

    /**
     * Offline authentication is disabled for Cloud-Only operation.
     * Firebase Authentication is required.
     */
    fun signInOffline(
        email: String,
        pass: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        onError("Offline login is disabled. Connect to the internet and sign in using Firebase.")
    }

    fun signInFirebase(email: String, pass: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                authRepo.signIn(email, pass)
            }.onSuccess {
                _session.value = it
            }.onFailure { err ->
                onError(err.message ?: "Sign-in error")
            }
        }
    }

    fun signOut() {
        authRepo.signOut()
        _session.value = null
        _cloudStockSnapshot.value = null
    }

    fun refreshCloudStock() {
        val s = _session.value ?: return
        if (!s.isCloud) return
        viewModelScope.launch {
            _cloudStockLoading.value = true
            _cloudStockError.value = null
            try {
                val snapshot = cloudRepo.fetchStock(s)
                _cloudStockSnapshot.value = snapshot
            } catch (e: Exception) {
                _cloudStockError.value = e.message ?: "Unable to load authoritative cloud stock"
            } finally {
                _cloudStockLoading.value = false
            }
        }
    }
}
