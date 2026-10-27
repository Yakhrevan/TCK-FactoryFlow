import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";

initializeApp();
const db = getFirestore();

type DispatchLineInput = { productId: string; packs: number; looseBottles?: number };
type DispatchInput = {
  organisationId: string;
  factoryId: string;
  idempotencyKey: string;
  dispatchReference: string;
  dispatchDate: string;
  customerId: string;
  customerName: string;
  lines: DispatchLineInput[];
  remarks?: string;
};

function requiredString(value: unknown, label: string): string {
  if (typeof value !== "string" || value.trim().length === 0 || value.length > 180) {
    throw new HttpsError("invalid-argument", label + " is required.");
  }
  return value.trim();
}
function positiveInteger(value: unknown, label: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
    throw new HttpsError("invalid-argument", label + " must be a positive integer.");
  }
  return value as number;
}
async function authorize(
  uid: string,
  orgId: string,
  factoryId: string,
  allowedRoles: string[]
): Promise<{ role: string; memberRef: FirebaseFirestore.DocumentReference }> {
  const memberRef = db.doc(`organisations/${orgId}/members/${uid}`);
  const snap = await memberRef.get();
  if (!snap.exists) throw new HttpsError("permission-denied", "Organisation membership is required.");
  const member = snap.data()!;
  const factoryIds = member.factoryIds;
  if (member.active !== true || member.organisationId !== orgId ||
      !Array.isArray(factoryIds) || !factoryIds.includes(factoryId)) {
    throw new HttpsError("permission-denied", "Active membership for this factory is required.");
  }
  const role = typeof member.role === "string" ? member.role : "";
  if (!allowedRoles.includes(role)) throw new HttpsError("permission-denied", "Role cannot perform this operation.");
  return { role, memberRef };
}

/**
 * Authoritative multi-line dispatch. All stock reads and writes happen in one Firestore
 * transaction; the idempotency record prevents duplicate posting on client retries.
 *
 * Required schema:
 * organisations/{orgId}/members/{uid}
 * organisations/{orgId}/factories/{factoryId}/products/{productId}
 * organisations/{orgId}/factories/{factoryId}/stock/{productId}
 * organisations/{orgId}/factories/{factoryId}/dispatches/{idempotencyKey}
 * organisations/{orgId}/factories/{factoryId}/inventoryTransactions/{transactionId}
 */
export const postDispatch = onCall({ enforceAppCheck: true }, async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in required.");
  const data = request.data as Partial<DispatchInput>;
  const orgId = requiredString(data.organisationId, "organisationId");
  const factoryId = requiredString(data.factoryId, "factoryId");
  const key = requiredString(data.idempotencyKey, "idempotencyKey");
  const reference = requiredString(data.dispatchReference, "dispatchReference");
  const date = requiredString(data.dispatchDate, "dispatchDate");
  const customerId = requiredString(data.customerId, "customerId");
  const customerName = requiredString(data.customerName, "customerName");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new HttpsError("invalid-argument", "dispatchDate must be YYYY-MM-DD.");
  if (!Array.isArray(data.lines) || data.lines.length < 1 || data.lines.length > 50) {
    throw new HttpsError("invalid-argument", "Dispatch must contain 1 to 50 lines.");
  }
  const merged = new Map<string, { packs: number; looseBottles: number }>();
  for (const raw of data.lines) {
    const productId = requiredString(raw?.productId, "productId");
    const packs = raw?.packs;
    const looseBottles = raw?.looseBottles ?? 0;
    if (typeof packs !== "number" || !Number.isSafeInteger(packs) || packs < 0 ||
        typeof looseBottles !== "number" || !Number.isSafeInteger(looseBottles) || looseBottles < 0 ||
        (packs === 0 && looseBottles === 0)) {
      throw new HttpsError("invalid-argument", "Each line needs non-negative whole packs and/or loose bottles.");
    }
    const prior = merged.get(productId) ?? { packs: 0, looseBottles: 0 };
    merged.set(productId, { packs: prior.packs + packs, looseBottles: prior.looseBottles + looseBottles });
  }
  const lines = [...merged.entries()].map(([productId, quantities]) => ({ productId, ...quantities }));
  const remarks = typeof data.remarks === "string" ? data.remarks.slice(0, 2000) : "";
  // Bind a retry key to the exact intended operation, not merely to an existing dispatch ID.
  const fingerprint = JSON.stringify({
    organisationId: orgId, factoryId, actorUid: request.auth.uid,
    dispatchReference: reference, dispatchDate: date, customerId,
    customerName, lines, remarks,
  });
  const { role } = await authorize(request.auth.uid, orgId, factoryId, ["owner", "admin", "manager", "dispatch"]);
  const root = `organisations/${orgId}/factories/${factoryId}`;
  const dispatchRef = db.doc(`${root}/dispatches/${key}`);
  const customerRef = db.doc(`${root}/customers/${customerId}`);
  const productRefs = lines.map((line) => db.doc(`${root}/products/${line.productId}`));
  const stockRefs = lines.map((line) => db.doc(`${root}/stock/${line.productId}`));
  const result = await db.runTransaction(async (tx) => {
    const existing = await tx.get(dispatchRef);
    if (existing.exists) {
      const prior = existing.data()!;
      if (prior.actorUid !== request.auth!.uid ||
          prior.organisationId !== orgId ||
          prior.factoryId !== factoryId ||
          prior.requestFingerprint !== fingerprint) {
        throw new HttpsError("already-exists", "This idempotency key was already used for a different dispatch.");
      }
      return { dispatchId: dispatchRef.id, status: prior.status as string, duplicate: true };
    }
    const customerSnap = await tx.get(customerRef);
    if (!customerSnap.exists || customerSnap.data()?.active === false) {
      throw new HttpsError("failed-precondition", "Customer does not exist or is inactive.");
    }
    // Complete all reads before any writes, as required by Firestore transactions.
    const productSnaps = await Promise.all(productRefs.map((ref) => tx.get(ref)));
    const stockSnaps = await Promise.all(stockRefs.map((ref) => tx.get(ref)));
    const postedLines = lines.map((line, index) => {
      const productSnap = productSnaps[index];
      const stockSnap = stockSnaps[index];
      if (!productSnap.exists) throw new HttpsError("not-found", `Product ${line.productId} not found.`);
      const product = productSnap.data()!;
      if (product.active !== true) throw new HttpsError("failed-precondition", `Product ${line.productId} is inactive.`);
      const packSize = product.bottlesPerPack;
      if (!Number.isSafeInteger(packSize) || packSize <= 0) {
        throw new HttpsError("failed-precondition", `Pack size is not configured for ${product.name ?? line.productId}.`);
      }
      const stock: FirebaseFirestore.DocumentData = stockSnap.exists ? stockSnap.data()! : {};
      const availablePacks = stock.completePacks;
      const availableLooseBottles = stock.looseBottles ?? 0;
      if (!Number.isSafeInteger(availablePacks) || availablePacks < 0 ||
          !Number.isSafeInteger(availableLooseBottles) || availableLooseBottles < 0) {
        throw new HttpsError("failed-precondition", `Authoritative stock balance is missing or invalid for ${product.name ?? line.productId}.`);
      }
      if (line.packs > availablePacks) {
        throw new HttpsError("failed-precondition", `Insufficient stock for ${product.name ?? line.productId}: ${availablePacks} packs available.`);
      }
      if (line.looseBottles > availableLooseBottles) {
        throw new HttpsError("failed-precondition", `Insufficient loose-bottle stock for ${product.name ?? line.productId}: ${availableLooseBottles} bottles available.`);
      }
      if (!Number.isSafeInteger(line.packs * packSize + line.looseBottles)) throw new HttpsError("invalid-argument", "Bottle equivalent exceeds the supported integer range.");
      return {
        productId: line.productId,
        productName: String(product.name ?? line.productId),
        packSizeSnapshot: packSize,
        packs: line.packs,
        looseBottles: line.looseBottles,
        bottles: line.packs * packSize + line.looseBottles,
        availablePacks,
        availableLooseBottles,
      };
    });
    tx.create(dispatchRef, {
      organisationId: orgId, factoryId, idempotencyKey: key, requestFingerprint: fingerprint,
      dispatchReference: reference, dispatchDate: date, customerId, customerName,
      lines: postedLines.map(({ availablePacks: _availablePacks, availableLooseBottles: _availableLoose, ...line }) => line),
      remarks,
      status: "POSTED", actorUid: request.auth!.uid, actorRole: role,
      createdAt: FieldValue.serverTimestamp(), reversalId: null,
    });
    postedLines.forEach((line, index) => {
      tx.update(stockRefs[index], {
        completePacks: line.availablePacks - line.packs,
        looseBottles: line.availableLooseBottles - line.looseBottles,
        updatedAt: FieldValue.serverTimestamp(),
      });
      if (line.packs > 0) {
        tx.create(db.collection(`${root}/inventoryTransactions`).doc(), {
          organisationId: orgId, factoryId, productId: line.productId,
          productNameSnapshot: line.productName, quantity: -line.packs, unit: "PACK",
          transactionType: "DISPATCH_OUT", sourceType: "dispatch", sourceId: key,
          idempotencyKey: `${key}:${line.productId}:packs`, actorUid: request.auth!.uid,
          createdAt: FieldValue.serverTimestamp(),
        });
      }
      if (line.looseBottles > 0) {
        tx.create(db.collection(`${root}/inventoryTransactions`).doc(), {
          organisationId: orgId, factoryId, productId: line.productId,
          productNameSnapshot: line.productName, quantity: -line.looseBottles, unit: "BOTTLE",
          transactionType: "DISPATCH_LOOSE_OUT", sourceType: "dispatch", sourceId: key,
          idempotencyKey: `${key}:${line.productId}:loose`, actorUid: request.auth!.uid,
          createdAt: FieldValue.serverTimestamp(),
        });
      }
    });
    return { dispatchId: dispatchRef.id, status: "POSTED", duplicate: false };
  });
  return result;
});

/** Product master provisioning. Product code is persistent identity, not a mutable display name. */
export const upsertProduct = onCall({ enforceAppCheck:true }, async (request) => {
 if(!request.auth) throw new HttpsError("unauthenticated","Sign in required.");
 const orgId=requiredString(request.data?.organisationId,"organisationId"), factoryId=requiredString(request.data?.factoryId,"factoryId"), productId=requiredString(request.data?.productId,"productId");
 const name=requiredString(request.data?.name,"name"), sizeLabel=requiredString(request.data?.sizeLabel,"sizeLabel"), colour=requiredString(request.data?.colour,"colour"), packSize=positiveInteger(request.data?.bottlesPerPack,"bottlesPerPack");
 const active=request.data?.active===true, {role}=await authorize(request.auth.uid,orgId,factoryId,["owner","admin","manager"]);
 const root="organisations/"+orgId+"/factories/"+factoryId, productRef=db.doc(root+"/products/"+productId);
 return db.runTransaction(async(tx)=>{
  const snap=await tx.get(productRef);
  if(snap.exists&&snap.data()?.productId!==productId) throw new HttpsError("already-exists","Product identity collision.");
  tx.set(productRef,{organisationId:orgId,factoryId,productId,name,sizeLabel,colour,bottlesPerPack:packSize,active,updatedBy:request.auth!.uid,updatedRole:role,updatedAt:FieldValue.serverTimestamp(),createdAt:snap.exists?(snap.data()!.createdAt??FieldValue.serverTimestamp()):FieldValue.serverTimestamp()},{merge:true});
  return {productId,active,updated:true};
 });
});

/** Read-only authoritative stock snapshot for the signed-in factory. */
export const getInventorySnapshot = onCall({ enforceAppCheck:true }, async (request) => {
 if(!request.auth) throw new HttpsError("unauthenticated","Sign in required.");
 const orgId=requiredString(request.data?.organisationId,"organisationId"), factoryId=requiredString(request.data?.factoryId,"factoryId");
 await authorize(request.auth.uid,orgId,factoryId,["owner","admin","manager","operator","production","production_staff","packing","packing_staff","storekeeper","dispatch","dispatch_staff","viewer"]);
 const root="organisations/"+orgId+"/factories/"+factoryId;
 const [products,stocks]=await Promise.all([db.collection(root+"/products").get(),db.collection(root+"/stock").get()]);
 const stockById=new Map(stocks.docs.map((doc)=>[doc.id,doc.data()]));
 return {organisationId:orgId,factoryId,serverTime:new Date().toISOString(),items:products.docs.map((doc)=>{const p=doc.data(),stock=stockById.get(doc.id)??{};return {productId:doc.id,name:String(p.name??doc.id),sizeLabel:String(p.sizeLabel??""),colour:String(p.colour??""),bottlesPerPack:Number(p.bottlesPerPack??0),active:p.active===true,unpackedGoodBottles:Number(stock.unpackedGoodBottles??0),completePacks:Number(stock.completePacks??0),looseBottles:Number(stock.looseBottles??0),updatedAt:stock.updatedAt??null};})};
});
type ProductionInput = { organisationId:string; factoryId:string; idempotencyKey:string; productId:string; productionDate:string; producedBottles:number; goodBottles:number; rejectedBottles:number; machineId?:string; lineId?:string; remarks?:string };
type PackingInput = { organisationId:string; factoryId:string; idempotencyKey:string; productId:string; packingDate:string; bottlesConsumed:number; remarks?:string };
function nonNegativeInteger(value:unknown,label:string):number { if(typeof value!=="number"||!Number.isSafeInteger(value)||value<0) throw new HttpsError("invalid-argument",label+" must be a non-negative whole number."); return value; }
function validDate(value:unknown,label:string):string { const date=requiredString(value,label); if(!/^\\d{4}-\\d{2}-\\d{2}$/.test(date)||Number.isNaN(Date.parse(date+"T00:00:00Z"))) throw new HttpsError("invalid-argument",label+" must be a valid YYYY-MM-DD date."); return date; }

/** Server-authoritative production posting. Stock updates and ledger entries commit atomically. */
export const postProduction = onCall({ enforceAppCheck:true }, async (request) => {
 if(!request.auth) throw new HttpsError("unauthenticated","Sign in required.");
 const data=request.data as Partial<ProductionInput>;
 const orgId=requiredString(data.organisationId,"organisationId"), factoryId=requiredString(data.factoryId,"factoryId"), key=requiredString(data.idempotencyKey,"idempotencyKey"), productId=requiredString(data.productId,"productId");
 const date=validDate(data.productionDate,"productionDate"), produced=nonNegativeInteger(data.producedBottles,"producedBottles"), good=nonNegativeInteger(data.goodBottles,"goodBottles"), rejected=nonNegativeInteger(data.rejectedBottles,"rejectedBottles");
 if(produced<=0||good+rejected!==produced||!Number.isSafeInteger(good+rejected)) throw new HttpsError("invalid-argument","Produced bottles must equal good plus rejected bottles, and production must be positive.");
 const machineId=typeof data.machineId==="string"?data.machineId:"", lineId=typeof data.lineId==="string"?data.lineId:"", remarks=typeof data.remarks==="string"?data.remarks.slice(0,2000):"";
 const fingerprint=JSON.stringify({orgId,factoryId,actorUid:request.auth.uid,productId,date,produced,good,rejected,machineId,lineId,remarks});
 const {role}=await authorize(request.auth.uid,orgId,factoryId,["owner","admin","manager","operator","production","production_staff"]);
 const root="organisations/"+orgId+"/factories/"+factoryId, runRef=db.doc(root+"/productions/"+key), productRef=db.doc(root+"/products/"+productId), stockRef=db.doc(root+"/stock/"+productId), machineRef=machineId?db.doc(root+"/machines/"+machineId):null;
 return db.runTransaction(async(tx)=>{
  const existing=await tx.get(runRef); if(existing.exists){const prior=existing.data()!; if(prior.actorUid!==request.auth!.uid||prior.organisationId!==orgId||prior.factoryId!==factoryId||prior.requestFingerprint!==fingerprint) throw new HttpsError("already-exists","This idempotency key was already used for a different production run."); return {productionId:runRef.id,status:prior.status as string,duplicate:true};}
  const productSnap=await tx.get(productRef), stockSnap=await tx.get(stockRef), machineSnap=machineRef?await tx.get(machineRef):null;
  if(!productSnap.exists||productSnap.data()?.active!==true) throw new HttpsError("failed-precondition","Product is missing or inactive in the authoritative factory catalog.");
  if(machineRef&&(!machineSnap?.exists||machineSnap.data()?.status!=="RUNNING")) throw new HttpsError("failed-precondition","Selected machine is missing or not running.");
  const stock=stockSnap.exists?stockSnap.data()!: {}, unpacked=stock.unpackedGoodBottles??0;
  if(!Number.isSafeInteger(unpacked)||unpacked<0||!Number.isSafeInteger(unpacked+good)) throw new HttpsError("failed-precondition","Unpacked bottle stock is invalid or exceeds the supported range.");
  tx.create(runRef,{organisationId:orgId,factoryId,idempotencyKey:key,requestFingerprint:fingerprint,productId,productNameSnapshot:String(productSnap.data()?.name??productId),productionDate:date,producedBottles:produced,goodBottles:good,rejectedBottles:rejected,machineId,lineId,remarks,status:"POSTED",actorUid:request.auth!.uid,actorRole:role,createdAt:FieldValue.serverTimestamp(),reversalId:null});
  tx.set(stockRef,{organisationId:orgId,factoryId,productId,unpackedGoodBottles:unpacked+good,completePacks:stock.completePacks??0,looseBottles:stock.looseBottles??0,updatedAt:FieldValue.serverTimestamp()},{merge:true});
  const name=String(productSnap.data()?.name??productId);
  if(good>0) tx.create(db.collection(root+"/inventoryTransactions").doc(),{organisationId:orgId,factoryId,productId,productNameSnapshot:name,quantity:good,unit:"BOTTLE",transactionType:"PRODUCTION_GOOD_IN",sourceType:"production",sourceId:key,idempotencyKey:key+":"+productId+":good",actorUid:request.auth!.uid,createdAt:FieldValue.serverTimestamp()});
  if(rejected>0) tx.create(db.collection(root+"/inventoryTransactions").doc(),{organisationId:orgId,factoryId,productId,productNameSnapshot:name,quantity:-rejected,unit:"BOTTLE",transactionType:"PRODUCTION_REJECTED",sourceType:"production",sourceId:key,idempotencyKey:key+":"+productId+":rejected",actorUid:request.auth!.uid,createdAt:FieldValue.serverTimestamp()});
  return {productionId:runRef.id,status:"POSTED",duplicate:false,unpackedGoodBottles:unpacked+good};
 });
});

/** Server-authoritative packing: consume unpacked good bottles and create finished stock atomically. */
export const postPacking = onCall({ enforceAppCheck:true }, async (request) => {
 if(!request.auth) throw new HttpsError("unauthenticated","Sign in required.");
 const data=request.data as Partial<PackingInput>, orgId=requiredString(data.organisationId,"organisationId"), factoryId=requiredString(data.factoryId,"factoryId"), key=requiredString(data.idempotencyKey,"idempotencyKey"), productId=requiredString(data.productId,"productId"), date=validDate(data.packingDate,"packingDate"), consumed=positiveInteger(data.bottlesConsumed,"bottlesConsumed"), remarks=typeof data.remarks==="string"?data.remarks.slice(0,2000):"";
 const fingerprint=JSON.stringify({orgId,factoryId,actorUid:request.auth.uid,productId,date,consumed,remarks});
 const {role}=await authorize(request.auth.uid,orgId,factoryId,["owner","admin","manager","operator","packing","packing_staff"]);
 const root="organisations/"+orgId+"/factories/"+factoryId, packingRef=db.doc(root+"/packings/"+key), productRef=db.doc(root+"/products/"+productId), stockRef=db.doc(root+"/stock/"+productId);
 return db.runTransaction(async(tx)=>{
  const existing=await tx.get(packingRef); if(existing.exists){const prior=existing.data()!; if(prior.actorUid!==request.auth!.uid||prior.organisationId!==orgId||prior.factoryId!==factoryId||prior.requestFingerprint!==fingerprint) throw new HttpsError("already-exists","This idempotency key was already used for a different packing run."); return {packingId:packingRef.id,status:prior.status as string,duplicate:true};}
  const productSnap=await tx.get(productRef), stockSnap=await tx.get(stockRef);
  if(!productSnap.exists||productSnap.data()?.active!==true) throw new HttpsError("failed-precondition","Product is missing or inactive in the authoritative factory catalog.");
  const packSize=productSnap.data()?.bottlesPerPack; if(!Number.isSafeInteger(packSize)||packSize<=0) throw new HttpsError("failed-precondition","Product pack size is not configured in the authoritative catalog.");
  const stock=stockSnap.exists?stockSnap.data()!: {}, unpacked=stock.unpackedGoodBottles??0, packs=Math.floor(consumed/packSize), loose=consumed%packSize, completePacks=stock.completePacks??0, looseBottles=stock.looseBottles??0;
  if(!Number.isSafeInteger(unpacked)||unpacked<consumed||!Number.isSafeInteger(completePacks)||completePacks<0||!Number.isSafeInteger(looseBottles)||looseBottles<0||!Number.isSafeInteger(completePacks+packs)||!Number.isSafeInteger(looseBottles+loose)) throw new HttpsError("failed-precondition","Insufficient or invalid unpacked bottle stock. Available: "+(Number.isSafeInteger(unpacked)?unpacked:0)+".");
  const name=String(productSnap.data()?.name??productId);
  tx.create(packingRef,{organisationId:orgId,factoryId,idempotencyKey:key,requestFingerprint:fingerprint,productId,productNameSnapshot:name,packingDate:date,bottlesConsumed:consumed,completePacks:packs,looseBottles:loose,remarks,status:"POSTED",actorUid:request.auth!.uid,actorRole:role,createdAt:FieldValue.serverTimestamp(),reversalId:null});
  tx.update(stockRef,{unpackedGoodBottles:unpacked-consumed,completePacks:completePacks+packs,looseBottles:looseBottles+loose,updatedAt:FieldValue.serverTimestamp()});
  tx.create(db.collection(root+"/inventoryTransactions").doc(),{organisationId:orgId,factoryId,productId,productNameSnapshot:name,quantity:-consumed,unit:"BOTTLE",transactionType:"PACKING_BOTTLES_OUT",sourceType:"packing",sourceId:key,idempotencyKey:key+":"+productId+":bottles-out",actorUid:request.auth!.uid,createdAt:FieldValue.serverTimestamp()});
  if(packs>0) tx.create(db.collection(root+"/inventoryTransactions").doc(),{organisationId:orgId,factoryId,productId,productNameSnapshot:name,quantity:packs,unit:"PACK",transactionType:"PACKING_PACKS_IN",sourceType:"packing",sourceId:key,idempotencyKey:key+":"+productId+":packs-in",actorUid:request.auth!.uid,createdAt:FieldValue.serverTimestamp()});
  if(loose>0) tx.create(db.collection(root+"/inventoryTransactions").doc(),{organisationId:orgId,factoryId,productId,productNameSnapshot:name,quantity:loose,unit:"BOTTLE",transactionType:"PACKING_LOOSE_IN",sourceType:"packing",sourceId:key,idempotencyKey:key+":"+productId+":loose-in",actorUid:request.auth!.uid,createdAt:FieldValue.serverTimestamp()});
  return {packingId:packingRef.id,status:"POSTED",duplicate:false,completePacks:packs,looseBottles:loose};
 });
});

/** Packing reversal is allowed only while its finished stock remains available. */
export const reversePacking = onCall({ enforceAppCheck:true }, async (request) => {
 if(!request.auth) throw new HttpsError("unauthenticated","Sign in required.");
 const orgId=requiredString(request.data?.organisationId,"organisationId"), factoryId=requiredString(request.data?.factoryId,"factoryId"), packingId=requiredString(request.data?.packingId,"packingId"), reason=requiredString(request.data?.reason,"reason");
 const {role}=await authorize(request.auth.uid,orgId,factoryId,["owner","admin","manager"]), root="organisations/"+orgId+"/factories/"+factoryId, packingRef=db.doc(root+"/packings/"+packingId), reversalRef=db.doc(root+"/packingReversals/"+packingId);
 return db.runTransaction(async(tx)=>{
  const packingSnap=await tx.get(packingRef), reversalSnap=await tx.get(reversalRef);
  if(!packingSnap.exists) throw new HttpsError("not-found","Packing record not found."); if(reversalSnap.exists) return {reversed:false,duplicate:true};
  const packing=packingSnap.data()!; if(packing.status!=="POSTED"||packing.organisationId!==orgId||packing.factoryId!==factoryId) throw new HttpsError("failed-precondition","Only a posted packing run in this factory can be reversed.");
  const productId=String(packing.productId??""), consumed=packing.bottlesConsumed, packs=packing.completePacks, loose=packing.looseBottles;
  if(!productId||!Number.isSafeInteger(consumed)||consumed<=0||!Number.isSafeInteger(packs)||packs<0||!Number.isSafeInteger(loose)||loose<0) throw new HttpsError("failed-precondition","Packing quantities are invalid.");
  const stockRef=db.doc(root+"/stock/"+productId), stockSnap=await tx.get(stockRef); if(!stockSnap.exists) throw new HttpsError("failed-precondition","Stock balance is missing.");
  const stock=stockSnap.data()!, unpacked=stock.unpackedGoodBottles??0, currentPacks=stock.completePacks??0, currentLoose=stock.looseBottles??0;
  if(!Number.isSafeInteger(unpacked)||unpacked<0||!Number.isSafeInteger(currentPacks)||currentPacks<packs||!Number.isSafeInteger(currentLoose)||currentLoose<loose) throw new HttpsError("failed-precondition","Cannot reverse packing because finished stock has already been dispatched or adjusted.");
  tx.create(reversalRef,{organisationId:orgId,factoryId,packingId,reason,actorUid:request.auth!.uid,actorRole:role,createdAt:FieldValue.serverTimestamp()});
  tx.update(packingRef,{status:"REVERSED",reversalId:reversalRef.id,reversedAt:FieldValue.serverTimestamp()}); tx.update(stockRef,{unpackedGoodBottles:unpacked+consumed,completePacks:currentPacks-packs,looseBottles:currentLoose-loose,updatedAt:FieldValue.serverTimestamp()});
  const name=String(packing.productNameSnapshot??productId);
  tx.create(db.collection(root+"/inventoryTransactions").doc(),{organisationId:orgId,factoryId,productId,productNameSnapshot:name,quantity:consumed,unit:"BOTTLE",transactionType:"PACKING_REVERSAL_BOTTLES_IN",sourceType:"packingReversal",sourceId:packingId,idempotencyKey:"reverse:"+packingId+":"+productId+":bottles",reason,actorUid:request.auth!.uid,createdAt:FieldValue.serverTimestamp()});
  if(packs>0) tx.create(db.collection(root+"/inventoryTransactions").doc(),{organisationId:orgId,factoryId,productId,productNameSnapshot:name,quantity:-packs,unit:"PACK",transactionType:"PACKING_REVERSAL_PACKS_OUT",sourceType:"packingReversal",sourceId:packingId,idempotencyKey:"reverse:"+packingId+":"+productId+":packs",reason,actorUid:request.auth!.uid,createdAt:FieldValue.serverTimestamp()});
  if(loose>0) tx.create(db.collection(root+"/inventoryTransactions").doc(),{organisationId:orgId,factoryId,productId,productNameSnapshot:name,quantity:-loose,unit:"BOTTLE",transactionType:"PACKING_REVERSAL_LOOSE_OUT",sourceType:"packingReversal",sourceId:packingId,idempotencyKey:"reverse:"+packingId+":"+productId+":loose",reason,actorUid:request.auth!.uid,createdAt:FieldValue.serverTimestamp()});
  return {reversed:true,duplicate:false};
 });
});
export const reverseDispatch = onCall({ enforceAppCheck: true }, async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in required.");
  const orgId = requiredString(request.data?.organisationId, "organisationId");
  const factoryId = requiredString(request.data?.factoryId, "factoryId");
  const dispatchId = requiredString(request.data?.dispatchId, "dispatchId");
  const reason = requiredString(request.data?.reason, "reason");
  const { role } = await authorize(request.auth.uid, orgId, factoryId, ["owner", "admin", "manager"]);
  const root = `organisations/${orgId}/factories/${factoryId}`;
  const dispatchRef = db.doc(`${root}/dispatches/${dispatchId}`);
  const reversalRef = db.doc(`${root}/dispatchReversals/${dispatchId}`);
  return db.runTransaction(async (tx) => {
    const dispatchSnap = await tx.get(dispatchRef);
    const reversalSnap = await tx.get(reversalRef);
    if (!dispatchSnap.exists) throw new HttpsError("not-found", "Dispatch not found.");
    if (reversalSnap.exists) return { reversed: false, duplicate: true };
    const dispatch = dispatchSnap.data()!;
    if (dispatch.status !== "POSTED") throw new HttpsError("failed-precondition", "Only posted dispatches can be reversed.");
    if (dispatch.organisationId !== orgId || dispatch.factoryId !== factoryId) {
      throw new HttpsError("permission-denied", "Dispatch scope mismatch.");
    }
    const lines = dispatch.lines as Array<{productId: string; productName: string; packs: number; looseBottles?: number; bottles: number}>;
    if (!Array.isArray(lines) || lines.length === 0) throw new HttpsError("failed-precondition", "Dispatch lines are missing.");
    const stockRefs = lines.map((line) => db.doc(`${root}/stock/${line.productId}`));
    const stockSnaps = await Promise.all(stockRefs.map((ref) => tx.get(ref)));
    lines.forEach((line, index) => {
      const loose = line.looseBottles ?? 0;
      if (!Number.isSafeInteger(line.packs) || line.packs < 0 ||
          !Number.isSafeInteger(loose) || loose < 0 || (line.packs === 0 && loose === 0) ||
          !stockSnaps[index].exists) {
        throw new HttpsError("failed-precondition", "Cannot safely restore inventory; stock record or quantities are invalid.");
      }
    });
    tx.create(reversalRef, {
      organisationId: orgId, factoryId, dispatchId, reason,
      actorUid: request.auth!.uid, actorRole: role, createdAt: FieldValue.serverTimestamp(),
    });
    tx.update(dispatchRef, { status: "REVERSED", reversalId: reversalRef.id, reversedAt: FieldValue.serverTimestamp() });
    lines.forEach((line, index) => {
      const stock = stockSnaps[index].data()!;
      const currentPacks = stock.completePacks;
      const currentLoose = stock.looseBottles ?? 0;
      const loose = line.looseBottles ?? 0;
      if (!Number.isSafeInteger(currentPacks) || currentPacks < 0 ||
          !Number.isSafeInteger(currentLoose) || currentLoose < 0) throw new HttpsError("failed-precondition", "Stock balance is invalid.");
      tx.update(stockRefs[index], {
        completePacks: currentPacks + line.packs,
        looseBottles: currentLoose + loose,
        updatedAt: FieldValue.serverTimestamp(),
      });
      if (line.packs > 0) tx.create(db.collection(`${root}/inventoryTransactions`).doc(), {
        organisationId: orgId, factoryId, productId: line.productId,
        productNameSnapshot: line.productName, quantity: line.packs, unit: "PACK",
        transactionType: "DISPATCH_REVERSAL_IN", sourceType: "dispatchReversal",
        sourceId: dispatchId, idempotencyKey: `reverse:${dispatchId}:${line.productId}:packs`,
        reason, actorUid: request.auth!.uid, createdAt: FieldValue.serverTimestamp(),
      });
      if (loose > 0) tx.create(db.collection(`${root}/inventoryTransactions`).doc(), {
        organisationId: orgId, factoryId, productId: line.productId,
        productNameSnapshot: line.productName, quantity: loose, unit: "BOTTLE",
        transactionType: "DISPATCH_LOOSE_REVERSAL_IN", sourceType: "dispatchReversal",
        sourceId: dispatchId, idempotencyKey: `reverse:${dispatchId}:${line.productId}:loose`,
        reason, actorUid: request.auth!.uid, createdAt: FieldValue.serverTimestamp(),
      });
    });
    return { reversed: true, duplicate: false };
  });
});
