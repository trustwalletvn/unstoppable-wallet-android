package io.horizontalsystems.bankwallet.modules.balance

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.core.subscribeIO
import io.horizontalsystems.bankwallet.entities.Account
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.coinkit.models.CoinType
import io.horizontalsystems.core.SingleLiveEvent
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BalanceViewModel(
    private val service: BalanceService2,
    private val rateAppService: RateAppService,
    private val activeAccountService: ActiveAccountService,
    private val balanceViewItemFactory: BalanceViewItemFactory,
    val reportEmail: String
) : ViewModel() {

    val titleLiveData = MutableLiveData<String>()
    val headerViewItemLiveData = MutableLiveData<BalanceHeaderViewItem>()
    val disabledWalletLiveData = SingleLiveEvent<Wallet>()

    private val _balanceViewItemsLiveData = MutableLiveData<List<BalanceViewItem>>()
    val balanceViewItems: LiveData<List<BalanceViewItem>> = _balanceViewItemsLiveData

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean>
        get() = _isRefreshing.asStateFlow()

    private var disposables = CompositeDisposable()

    var sortType: BalanceSortType by service::sortType

    private var expandedWallet: Wallet? = null

    init {
        service.disabledWalletSubject
            .subscribeIO {
                disabledWalletLiveData.postValue(it)
            }
            .let {
                disposables.add(it)
            }

        activeAccountService.activeAccountObservable
            .subscribeIO {
                titleLiveData.postValue(it.name)
            }
            .let {
                disposables.add(it)
            }

        service.balanceItemsObservable
            .subscribeIO {
                refreshViewItems()
                refreshHeaderViewItem()
            }
            .let {
                disposables.add(it)
            }
    }

    private fun refreshViewItems() {
        _balanceViewItemsLiveData.postValue(service.balanceItems.map { balanceItem ->
            balanceViewItemFactory.viewItem(
                balanceItem,
                service.baseCurrency,
                balanceItem.wallet == expandedWallet,
                service.balanceHidden
            )
        })
    }

    private fun refreshHeaderViewItem() {
        headerViewItemLiveData.postValue(
            balanceViewItemFactory.headerViewItem(
                service.balanceItems,
                service.baseCurrency,
                service.balanceHidden
            )
        )
    }

    fun onRefresh() {
        if (_isRefreshing.value) {
            return
        }

        viewModelScope.launch {
            service.refresh()
            // A fake 2 second 'refresh'
            _isRefreshing.emit(true)
            delay(1800)
            _isRefreshing.emit(false)
        }
    }

    fun onBalanceClick() {
        service.balanceHidden = !service.balanceHidden

        refreshViewItems()
        refreshHeaderViewItem()
    }

    fun onItem(viewItem: BalanceViewItem) {
        expandedWallet = when {
            viewItem.wallet == expandedWallet -> null
            else -> viewItem.wallet
        }

        refreshViewItems()
    }

    fun onResume() {
        rateAppService.onBalancePageActive()
    }

    fun onPause() {
        rateAppService.onBalancePageInactive()
    }

    fun getSyncErrorDetails(viewItem: BalanceViewItem): SyncError = when {
        service.networkAvailable -> {
            SyncError.Dialog(viewItem.wallet, viewItem.errorMessage ?: "", sourceChangeable(viewItem.wallet.coin.type))
        }
        else -> {
            SyncError.NetworkNotAvailable()
        }
    }

    private fun sourceChangeable(coinType: CoinType) = when (coinType) {
        is CoinType.Bep2,
        is CoinType.Ethereum,
        is CoinType.Erc20 -> false
        else -> true
    }

    fun refreshByWallet(wallet: Wallet) {
        service.refreshByWallet(wallet)
    }

    override fun onCleared() {
        activeAccountService.clear()
        service.clear()
        rateAppService.clear()
    }

    fun getWalletForReceive(viewItem: BalanceViewItem) = when {
        viewItem.wallet.account.isBackedUp -> viewItem.wallet
        else -> throw BackupRequiredError(viewItem.wallet.account)
    }

    fun disable(viewItem: BalanceViewItem) {
        service.disable(viewItem.wallet)
    }

    fun enable(wallet: Wallet) {
        service.enable(wallet)
    }

    sealed class SyncError {
        class NetworkNotAvailable : SyncError()
        class Dialog(val wallet: Wallet, val errorMessage: String, val sourceChangeable: Boolean) : SyncError()
    }

    class BackupRequiredError(val account: Account) : Error("Backup Required")

}
