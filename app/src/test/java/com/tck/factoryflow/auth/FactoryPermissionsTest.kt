package com.tck.factoryflow.auth
import org.junit.Assert.*
import org.junit.Test
class FactoryPermissionsTest {
 @Test fun productionStaffCannotDispatchOrAdjustStock(){assertTrue(FactoryPermissions.allows(FactoryRole.PRODUCTION_STAFF,FactoryAction.CREATE_PRODUCTION));assertFalse(FactoryPermissions.allows(FactoryRole.PRODUCTION_STAFF,FactoryAction.POST_DISPATCH));assertFalse(FactoryPermissions.allows(FactoryRole.PRODUCTION_STAFF,FactoryAction.ADJUST_STOCK))}
 @Test fun dispatchStaffCannotManageStaff(){assertTrue(FactoryPermissions.allows(FactoryRole.DISPATCH_STAFF,FactoryAction.POST_DISPATCH));assertFalse(FactoryPermissions.allows(FactoryRole.DISPATCH_STAFF,FactoryAction.MANAGE_STAFF))}
 @Test fun crossOrganisationAccessDenied(){FactoryPermissions.requireOrganisation("factory-a","factory-a");assertThrows(IllegalArgumentException::class.java){FactoryPermissions.requireOrganisation("factory-a","factory-b")}}
 @Test fun blankOrganisationDenied(){assertThrows(IllegalArgumentException::class.java){FactoryPermissions.requireOrganisation("","factory-a")}}
}
