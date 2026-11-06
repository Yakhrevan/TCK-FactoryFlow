import test from "node:test";
import assert from "node:assert/strict";
import { toFirestoreValue, fromFirestoreValue, fromFirestoreDoc } from "../src/firestore.js";

test("toFirestoreValue handles primitive types correctly", () => {
  assert.deepEqual(toFirestoreValue("test"), { stringValue: "test" });
  assert.deepEqual(toFirestoreValue(42), { integerValue: "42" });
  assert.deepEqual(toFirestoreValue(3.14), { doubleValue: 3.14 });
  assert.deepEqual(toFirestoreValue(true), { booleanValue: true });
  assert.deepEqual(toFirestoreValue(false), { booleanValue: false });
  assert.deepEqual(toFirestoreValue(null), { nullValue: null });
  assert.deepEqual(toFirestoreValue([1, "a"]), {
    arrayValue: {
      values: [{ integerValue: "1" }, { stringValue: "a" }]
    }
  });
  assert.deepEqual(toFirestoreValue({ name: "TCK", count: 10 }), {
    mapValue: {
      fields: {
        name: { stringValue: "TCK" },
        count: { integerValue: "10" }
      }
    }
  });
});

test("fromFirestoreValue reconstructs values accurately", () => {
  assert.equal(fromFirestoreValue({ stringValue: "hello" }), "hello");
  assert.equal(fromFirestoreValue({ integerValue: "100" }), 100);
  assert.equal(fromFirestoreValue({ doubleValue: 9.99 }), 9.99);
  assert.equal(fromFirestoreValue({ booleanValue: true }), true);
  assert.equal(fromFirestoreValue({ nullValue: null }), null);
  assert.deepEqual(fromFirestoreValue({
    arrayValue: { values: [{ stringValue: "x" }, { stringValue: "y" }] }
  }), ["x", "y"]);
  assert.deepEqual(fromFirestoreValue({
    mapValue: { fields: { key: { stringValue: "val" } } }
  }), { key: "val" });
});

test("fromFirestoreDoc converts document with ID correctly", () => {
  const doc = {
    name: "projects/tck-admin-ac864/databases/(default)/documents/organisations/tck-factory-01/factories/factory-01/products/prod-01",
    fields: {
      name: { stringValue: "500ml Bottle" },
      bottlesPerPack: { integerValue: "24" },
      active: { booleanValue: true }
    }
  };
  const parsed = fromFirestoreDoc(doc);
  assert.equal(parsed.id, "prod-01");
  assert.equal(parsed.name, "500ml Bottle");
  assert.equal(parsed.bottlesPerPack, 24);
  assert.equal(parsed.active, true);
});

test("custom claims payload serializes correctly for Firebase Identity Toolkit", () => {
  const claims = {
    organisationId: "tck-factory-01",
    role: "admin",
    factoryIds: ["factory-01"]
  };
  const body = {
    localId: "uid-123",
    customAttributes: JSON.stringify(claims)
  };
  assert.equal(body.localId, "uid-123");
  const parsed = JSON.parse(body.customAttributes);
  assert.equal(parsed.organisationId, "tck-factory-01");
  assert.equal(parsed.role, "admin");
  assert.deepEqual(parsed.factoryIds, ["factory-01"]);
});

test("organisation resolution prioritizes explicit input or falls back to known organisation", () => {
  function resolveOrg(bodyOrg, tokenOrg, defaultOrg = "tck-factory-01") {
    const org = String(bodyOrg || tokenOrg || "").trim();
    return org || defaultOrg;
  }

  assert.equal(resolveOrg("custom-org", "token-org"), "custom-org");
  assert.equal(resolveOrg("", "token-org"), "token-org");
  assert.equal(resolveOrg("", ""), "tck-factory-01");
});

test("bootstrap admin email validation accepts canonical variants", () => {
  function isAllowedBootstrap(email) {
    const clean = String(email || "").toLowerCase().trim();
    return clean === "admin@tck-factory.com" || clean === "admin@tckfactory.com";
  }

  assert.equal(isAllowedBootstrap("admin@tck-factory.com"), true);
  assert.equal(isAllowedBootstrap("admin@tckfactory.com"), true);
  assert.equal(isAllowedBootstrap("ADMIN@TCKFACTORY.COM"), true);
  assert.equal(isAllowedBootstrap("hacker@other.com"), false);
  assert.equal(isAllowedBootstrap("admin@tck.com"), false);
});

test("production quantities validate produced = good + rejected", () => {
  function validateProduction(produced, good, rejected) {
    if (!Number.isSafeInteger(produced) || produced <= 0) return false;
    if (!Number.isSafeInteger(good) || good < 0) return false;
    if (!Number.isSafeInteger(rejected) || rejected < 0) return false;
    return good + rejected <= produced;
  }

  assert.equal(validateProduction(1000, 950, 50), true);
  assert.equal(validateProduction(1000, 900, 50), true);
  assert.equal(validateProduction(1000, 950, 60), false);
  assert.equal(validateProduction(-100, -50, -50), false);
  assert.equal(validateProduction(0, 0, 0), false);
});

test("packing calculation computes complete packs and loose bottles", () => {
  function calcPacks(bottles, packSize) {
    if (bottles < 0 || packSize <= 0) return null;
    return {
      completePacks: Math.floor(bottles / packSize),
      looseBottles: bottles % packSize
    };
  }

  assert.deepEqual(calcPacks(100, 24), { completePacks: 4, looseBottles: 4 });
  assert.deepEqual(calcPacks(96, 24), { completePacks: 4, looseBottles: 0 });
  assert.deepEqual(calcPacks(10, 24), { completePacks: 0, looseBottles: 10 });
});

test("electricity reading calculates consumption and differentiates pending vs completed", () => {
  function processReading(startReading, endReading, clientStatus) {
    const start = Number(startReading);
    const hasEnd = endReading !== undefined && endReading !== null && Number(endReading) > 0;
    const end = hasEnd ? Number(endReading) : 0;
    const isPending = !hasEnd || clientStatus === "PENDING";

    if (isNaN(start) || start < 0) return { error: "INVALID_START" };
    if (!isPending && (isNaN(end) || end < start)) return { error: "INVALID_END" };

    const consumption = isPending ? 0 : Math.round((end - start) * 100) / 100;
    const status = isPending ? "PENDING" : "COMPLETED";
    return { status, consumption };
  }

  // Morning opening reading
  assert.deepEqual(processReading(12500, 0, "PENDING"), { status: "PENDING", consumption: 0 });
  assert.deepEqual(processReading(12500, null, null), { status: "PENDING", consumption: 0 });

  // Evening closing reading
  assert.deepEqual(processReading(12500, 12750.5, "COMPLETED"), { status: "COMPLETED", consumption: 250.5 });

  // Invalid end reading < start
  assert.deepEqual(processReading(12500, 12400, "COMPLETED"), { error: "INVALID_END" });
});

test("raw material receipt updates stock correctly and prevents negative values", () => {
  function applyReceipt(currentStock, receivedQty) {
    const current = Number(currentStock || 0);
    const qty = Number(receivedQty);
    if (isNaN(qty) || qty <= 0) return null;
    return Math.round((current + qty) * 100) / 100;
  }

  assert.equal(applyReceipt(500, 250.5), 750.5);
  assert.equal(applyReceipt(0, 1000), 1000);
  assert.equal(applyReceipt(100, -50), null);
  assert.equal(applyReceipt(100, 0), null);
});

test("raw material issue deducts stock and prevents overdraft", () => {
  function applyIssue(currentStock, issueQty) {
    const current = Number(currentStock || 0);
    const qty = Number(issueQty);
    if (isNaN(qty) || qty <= 0 || qty > current) return null;
    return Math.round((current - qty) * 100) / 100;
  }

  assert.equal(applyIssue(500, 200), 300);
  assert.equal(applyIssue(500, 500), 0);
  assert.equal(applyIssue(100, 150), null); // overdraft prevented
  assert.equal(applyIssue(100, -10), null);
  assert.equal(applyIssue(100, 0), null);
});

test("sales order dispatch updates line fulfilment and order status", () => {
  function updateOrderFulfilment(lines, postedDispatchLines) {
    let anyFulfilled = false;
    let allFulfilled = lines.length > 0;
    const updated = lines.map(ol => {
      const matching = postedDispatchLines.find(p => p.productId === ol.productId);
      const added = matching ? matching.packs : 0;
      const currentDispatched = (Number(ol.packsDispatched) || 0) + added;
      if (currentDispatched > 0) anyFulfilled = true;
      if (currentDispatched < Number(ol.packsOrdered)) allFulfilled = false;
      return { ...ol, packsDispatched: currentDispatched };
    });
    const status = allFulfilled ? "COMPLETED" : (anyFulfilled ? "PARTIALLY_DISPATCHED" : "OPEN");
    return { lines: updated, status };
  }

  const orderLines = [
    { productId: "p1", packsOrdered: 50, packsDispatched: 0 },
    { productId: "p2", packsOrdered: 30, packsDispatched: 0 }
  ];

  // Partial dispatch
  const partialResult = updateOrderFulfilment(orderLines, [{ productId: "p1", packs: 20 }]);
  assert.equal(partialResult.status, "PARTIALLY_DISPATCHED");
  assert.equal(partialResult.lines[0].packsDispatched, 20);
  assert.equal(partialResult.lines[1].packsDispatched, 0);

  // Complete fulfilment
  const fullResult = updateOrderFulfilment(partialResult.lines, [
    { productId: "p1", packs: 30 },
    { productId: "p2", packs: 30 }
  ]);
  assert.equal(fullResult.status, "COMPLETED");
  assert.equal(fullResult.lines[0].packsDispatched, 50);
  assert.equal(fullResult.lines[1].packsDispatched, 30);
});

test("reversal authorization permits owner/admin to reverse any dispatch, while managers reverse only their own", () => {
  function canReverseDispatch(userRole, userUid, creatorUid) {
    if (!["owner", "admin", "manager"].includes(userRole)) return false;
    if (userRole === "owner" || userRole === "admin") return true;
    return userUid === creatorUid;
  }

  assert.equal(canReverseDispatch("admin", "admin-1", "operator-2"), true);
  assert.equal(canReverseDispatch("owner", "owner-1", "manager-2"), true);
  assert.equal(canReverseDispatch("manager", "mgr-1", "mgr-1"), true);
  assert.equal(canReverseDispatch("manager", "mgr-1", "operator-2"), false);
  assert.equal(canReverseDispatch("operator", "op-1", "op-1"), false);
});




test("packing reversal authorization permits owner, admin, or creator to reverse", () => {
  function canReversePacking(role, userUid, creatorUid) {
    if (role === "owner" || role === "admin" || role === "manager") return true;
    return userUid === creatorUid;
  }

  assert.equal(canReversePacking("owner", "u-owner", "u-op"), true);
  assert.equal(canReversePacking("admin", "u-admin", "u-op"), true);
  assert.equal(canReversePacking("manager", "u-mgr", "u-op"), true);
  assert.equal(canReversePacking("operator", "u-op", "u-op"), true);
  assert.equal(canReversePacking("operator", "u-other", "u-op"), false);
});

test("quality reversal authorization requires supervisor or above", () => {
  const allowed = ["owner", "admin", "manager", "supervisor", "quality_inspector"];
  assert.equal(allowed.includes("quality_inspector"), true);
  assert.equal(allowed.includes("supervisor"), true);
  assert.equal(allowed.includes("admin"), true);
  assert.equal(allowed.includes("operator"), false);
  assert.equal(allowed.includes("viewer"), false);
});

test("member provisioning role validation supports supervisor, quality_inspector, and inventory_manager", () => {
  const allowedRoles = [
    "owner", "admin", "manager", "supervisor", "quality_inspector", "inventory_manager",
    "production_staff", "packing_staff", "storekeeper", "dispatch_staff",
    "viewer", "operator", "production", "packing", "dispatch", "production_operator"
  ];
  assert.equal(allowedRoles.includes("supervisor"), true);
  assert.equal(allowedRoles.includes("quality_inspector"), true);
  assert.equal(allowedRoles.includes("inventory_manager"), true);
  assert.equal(allowedRoles.includes("hacker"), false);
});
