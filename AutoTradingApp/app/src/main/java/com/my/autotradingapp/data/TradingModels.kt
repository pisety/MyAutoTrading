package com.my.autotradingapp.data

import com.google.firebase.database.IgnoreExtraProperties
import com.google.firebase.database.PropertyName

@IgnoreExtraProperties
data class AccountInfo(
    val broker: String = "",
    @get:PropertyName("_mock") @set:PropertyName("_mock")
    var is_mock: Boolean = true,
    @get:PropertyName("_overseas") @set:PropertyName("_overseas")
    var is_overseas: Boolean = true,
    val account_number: String = "",
    val app_key: String = "",
    val app_secret: String = "",
    val last_used_timestamp: String = ""
)

@IgnoreExtraProperties
data class UserAccountData(
    val active_account_id: String = "",
    val pin: String = "",
    val saved_accounts: Map<String, AccountInfo> = emptyMap()
)

@IgnoreExtraProperties
data class HoldingStock(
    val symbol: String = "",
    val quantity: Double = 0.0,
    val avg_price: Double = 0.0,
    val current_price: Double = 0.0,
    val profit_loss: Double = 0.0,
    val yield: Float = 0.0f,
    val name: String = ""
)

@IgnoreExtraProperties
data class TradingStatus(
    val total_balance: Double = 0.0,
    val current_yield: Float = 0.0f,
    val avail_cash: Double = 0.0,
    val last_updated: String = "",
    val stock_prices: Map<String, Double> = emptyMap(),
    val stock_prices_change: Map<String, Double> = emptyMap(),
    val stock_names: Map<String, String> = emptyMap(),
    val holding_stocks: Map<String, HoldingStock> = emptyMap(),
    val authError: String = ""
)

@IgnoreExtraProperties
data class SymbolSurgeConfig(
    val time_window_seconds: Int = 60,
    val surge_percent_threshold: Float = 3.0f,
    val buy_quantity: Double = 0.0
)

@IgnoreExtraProperties
data class SurgeTradingConfig(
    val enabled: Boolean = false,
    val allow_extended_hours: Boolean = true,
    val per_symbol_config: Map<String, List<SymbolSurgeConfig>> = emptyMap()
)

@IgnoreExtraProperties
data class TradingSettings(
    val is_auto_trading: Boolean = false,
    val watch_symbols: Map<String, SymbolSettings> = emptyMap(),
    val surge_trading: SurgeTradingConfig = SurgeTradingConfig(),
    val memo: String = ""
)

@IgnoreExtraProperties
data class SymbolSettings(
    val enabled: Boolean = true,
    val order: Int = 0
)

@IgnoreExtraProperties
data class ManualOrder(
    val command: String = "NONE",
    val symbol: String = "",
    val quantity: Double = 1.0,
    val target_price: Double = 0.0,
    val timestamp: String = "",
    val status: String = "",
    val error_message: String = ""
)

@IgnoreExtraProperties
data class ConditionalOrder(
    val symbol: String = "",
    val side: String = "BUY",
    val quantity: Double = 1.0,
    val target_price: Double = 0.0,
    val allow_extended_hours: Boolean = false,
    val status: String = "PENDING",
    val timestamp: String = "",
    val executed_at: String = "",
    val executed_price: Double = 0.0
)

@IgnoreExtraProperties
data class StopLossOrder(
    val symbol: String = "",
    val trigger_price: Double = 0.0,
    val volume_percent: Int = 100,
    val allow_extended_hours: Boolean = false,
    val status: String = "PENDING",
    val timestamp: String = "",
    val executed_at: String = "",
    val executed_price: Double = 0.0
)

@IgnoreExtraProperties
data class LaoreSettings(
    val enabled: Boolean = false,
    val total_budget: Double = 0.0,
    val split_count: Int = 40,
    val target_yield_percent: Float = 10.0f,
    val interval_hours: Float = 24.0f
)

@IgnoreExtraProperties
data class LaoreStatus(
    val current_round: Int = 0,
    val holding_quantity: Double = 0.0,
    val average_price: Double = 0.0,
    val status_message: String = "",
    val current_mode: String = "일반 모드(전반전)",
    val t_value: Double = 0.0,
    val target_sell_price: Double = 0.0,
    val remaining_cash: Double = 0.0
)

@IgnoreExtraProperties
data class ReservationOrder(
    val symbol: String = "",
    val name: String = "",
    val quantity: Double = 0.0,
    val auto_cancel_enabled: Boolean = false,
    val auto_cancel_threshold: Float = 1.0f,
    val status: String = "대기중",
    val order_no: String = "",
    val timestamp: String = "",
    val last_updated: String = ""
)


