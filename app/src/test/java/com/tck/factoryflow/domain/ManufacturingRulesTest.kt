package com.tck.factoryflow.domain
import org.junit.Assert.*
import org.junit.Test
class ManufacturingRulesTest {
 @Test fun pack300(){val r=PackCalculation.calculate(300,300);assertEquals(1,r.completePacks);assertEquals(0,r.looseBottles)}
 @Test fun pack650(){val r=PackCalculation.calculate(650,300);assertEquals(2,r.completePacks);assertEquals(50,r.looseBottles)}
 @Test fun pack299DoesNotRoundUp(){val r=PackCalculation.calculate(299,300);assertEquals(0,r.completePacks);assertEquals(299,r.looseBottles)}
 @Test fun zeroQuantityAllowedButNoPack(){val r=PackCalculation.calculate(0,300);assertEquals(0,r.completePacks);assertEquals(0,r.looseBottles)}
 @Test fun invalidPackSize(){assertThrows(IllegalArgumentException::class.java){PackCalculation.calculate(10,0)}}
 @Test fun negativeQuantityRejected(){assertThrows(IllegalArgumentException::class.java){PackCalculation.calculate(-1,300)}}
 @Test fun productionQuantitySumCannotOverflowIntegerRange(){assertThrows(IllegalArgumentException::class.java){ProductionQuantities(Int.MAX_VALUE,Int.MAX_VALUE,1).validate()}}
 @Test fun productionQuantitySumWithinRangeIsValid(){ProductionQuantities(100,70,30).validate()}
 @Test fun rejectsCannotExceedProduced(){assertThrows(IllegalArgumentException::class.java){ProductionQuantities(10,8,3).validate()}}
 @Test fun negativeProductionRejected(){assertThrows(IllegalArgumentException::class.java){ProductionQuantities(-1,0,0).validate()}}
 @Test fun electricityDecimal(){assertEquals(38.0,ElectricityRules.consumption(5065.3,5103.3),0.0001)}
 @Test fun decreasingMeterRejected(){assertThrows(IllegalArgumentException::class.java){ElectricityRules.consumption(30.0,29.0)}}
 @Test fun dispatchCannotOversell(){assertThrows(IllegalArgumentException::class.java){DispatchRules.remainingStock(2,3)}}
 @Test fun dispatchLeavesExpectedBalance(){assertEquals(1,DispatchRules.remainingStock(3,2))}
 @Test fun zeroDispatchRejected(){assertThrows(IllegalArgumentException::class.java){DispatchRules.remainingStock(2,0)}}
}
