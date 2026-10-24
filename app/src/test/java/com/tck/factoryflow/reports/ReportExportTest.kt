package com.tck.factoryflow.reports
import com.tck.factoryflow.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
class ReportExportTest {
 @Test fun rangeIncludesBothEnds(){assertTrue(ReportExport.inRange("2026-10-01",LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-31")));assertTrue(ReportExport.inRange("2026-10-31",LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-31")));assertFalse(ReportExport.inRange("2026-11-01",LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-31")))}
 @Test fun invalidRangeRejected(){assertThrows(IllegalArgumentException::class.java){ReportExport.inRange("2026-10-01",LocalDate.parse("2026-10-02"),LocalDate.parse("2026-10-01"))}}
 @Test fun csvEscapesQuotesAndIncludesSavedRecords(){val result=ReportExport.csv(listOf(ProductionEntryEntity(productId=1,productNameSnapshot="Clear, XL",productionDate="2026-10-24",producedBottles=100,goodBottles=98,rejectedBottles=2)),emptyList(),emptyList(),emptyList());assertTrue(result.contains("\"Clear, XL\""));assertTrue(result.contains("good=98; rejected=2"))}
}
