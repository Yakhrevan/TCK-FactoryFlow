package com.tck.factoryflow.domain

data class PackCalculation(val eligibleBottles: Int, val packSize: Int, val completePacks: Int, val looseBottles: Int) {
    companion object {
        fun calculate(eligibleBottles: Int, bottlesPerPack: Int): PackCalculation {
            require(eligibleBottles >= 0) { "Bottle quantity cannot be negative." }
            require(bottlesPerPack > 0) { "Pack size must be a positive integer." }
            return PackCalculation(eligibleBottles, bottlesPerPack, eligibleBottles / bottlesPerPack, eligibleBottles % bottlesPerPack)
        }
    }
}
data class ProductionQuantities(val produced: Int, val good: Int, val rejected: Int) {
    fun validate() {
        require(produced >= 0 && good >= 0 && rejected >= 0) { "Production quantities cannot be negative." }
        require(good + rejected <= produced) { "Good and rejected bottles cannot exceed total produced." }
    }
}
object ElectricityRules {
    fun consumption(startReading: Double, endReading: Double): Double {
        require(startReading.isFinite() && endReading.isFinite()) { "Meter readings must be finite numbers." }
        require(startReading >= 0.0 && endReading >= 0.0) { "Meter readings cannot be negative." }
        require(endReading >= startReading) { "End reading cannot be below start reading." }
        return kotlin.math.round((endReading - startReading) * 10.0) / 10.0
    }
}
object DispatchRules {
    fun remainingStock(availablePacks: Int, requestedPacks: Int): Int {
        require(availablePacks >= 0) { "Available stock cannot be negative." }
        require(requestedPacks > 0) { "Dispatch quantity must be positive." }
        require(requestedPacks <= availablePacks) { "Insufficient finished stock." }
        return availablePacks - requestedPacks
    }
}
