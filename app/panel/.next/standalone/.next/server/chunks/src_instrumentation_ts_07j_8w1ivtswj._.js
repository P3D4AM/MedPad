module.exports=[23322,T=>{"use strict";let E=['ALTER TABLE "AppToken" ADD COLUMN "expiresAt" DATETIME','ALTER TABLE "AppToken" ADD COLUMN "revokedAt" DATETIME','ALTER TABLE "Checkin" ADD COLUMN "accuracy" REAL','ALTER TABLE "Checkin" ADD COLUMN "distanceM" REAL','ALTER TABLE "Checkin" ADD COLUMN "outOfRadius" BOOLEAN NOT NULL DEFAULT 0','ALTER TABLE "Checkin" ADD COLUMN "locationMissing" BOOLEAN NOT NULL DEFAULT 0','ALTER TABLE "Employee" ADD COLUMN "firstName" TEXT','ALTER TABLE "Employee" ADD COLUMN "lastName" TEXT','ALTER TABLE "AppNotification" ADD COLUMN "entityType" TEXT','ALTER TABLE "AppNotification" ADD COLUMN "entityId" TEXT','ALTER TABLE "Checkin" ADD COLUMN "geoBypassed" BOOLEAN NOT NULL DEFAULT 0','ALTER TABLE "Visit" ADD COLUMN "deviationMin" INTEGER'],e=`CREATE TABLE IF NOT EXISTS "AuditLog" (
  "id" TEXT NOT NULL PRIMARY KEY,
  "actorType" TEXT NOT NULL DEFAULT 'admin',
  "actorId" TEXT NOT NULL,
  "action" TEXT NOT NULL,
  "targetType" TEXT,
  "targetId" TEXT,
  "detail" TEXT,
  "outcome" TEXT NOT NULL DEFAULT 'success',
  "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`,t=`CREATE TABLE IF NOT EXISTS "PlannedVisit" (
  "id" TEXT NOT NULL PRIMARY KEY,
  "employeeId" TEXT NOT NULL,
  "customerId" TEXT NOT NULL,
  "scheduledAt" DATETIME NOT NULL,
  "durationMin" INTEGER NOT NULL DEFAULT 30,
  "priority" TEXT NOT NULL DEFAULT 'normal',
  "status" TEXT NOT NULL DEFAULT 'scheduled',
  "note" TEXT,
  "visitId" TEXT,
  "createdById" TEXT,
  "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" DATETIME NOT NULL,
  CONSTRAINT "PlannedVisit_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "Employee" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT "PlannedVisit_customerId_fkey" FOREIGN KEY ("customerId") REFERENCES "Customer" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT "PlannedVisit_visitId_fkey" FOREIGN KEY ("visitId") REFERENCES "Visit" ("id") ON DELETE SET NULL ON UPDATE CASCADE
)`,N=`CREATE TABLE IF NOT EXISTS "PanelNotification" (
  "id" TEXT NOT NULL PRIMARY KEY,
  "eventKey" TEXT NOT NULL,
  "kind" TEXT NOT NULL DEFAULT 'info',
  "title" TEXT NOT NULL,
  "body" TEXT NOT NULL,
  "employeeId" TEXT,
  "customerId" TEXT,
  "readAt" DATETIME,
  "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`,A=`CREATE TABLE IF NOT EXISTS "OpEvent" (
  "id" TEXT NOT NULL PRIMARY KEY,
  "employeeId" TEXT,
  "type" TEXT NOT NULL,
  "status" TEXT NOT NULL DEFAULT 'success',
  "deviceAt" DATETIME,
  "serverAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "errorCode" TEXT,
  "message" TEXT,
  "relatedType" TEXT,
  "relatedId" TEXT,
  "requestId" TEXT,
  "attempt" INTEGER,
  "appVersion" TEXT
)`,L=`CREATE TABLE IF NOT EXISTS "AssignmentRequest" (
  "id" TEXT NOT NULL PRIMARY KEY,
  "employeeId" TEXT NOT NULL,
  "customerId" TEXT NOT NULL,
  "status" TEXT NOT NULL DEFAULT 'pending',
  "note" TEXT,
  "adminNote" TEXT,
  "decidedAt" DATETIME,
  "decidedBy" TEXT,
  "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" DATETIME NOT NULL
)`,i=['ALTER TABLE "Customer" ADD COLUMN "province" TEXT','ALTER TABLE "Customer" ADD COLUMN "city" TEXT'],n=`CREATE TABLE IF NOT EXISTS "CheckinNoteVersion" (
  "id" TEXT NOT NULL PRIMARY KEY,
  "checkinId" TEXT NOT NULL,
  "oldNote" TEXT,
  "newNote" TEXT,
  "editorId" TEXT NOT NULL,
  "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "CheckinNoteVersion_checkinId_fkey" FOREIGN KEY ("checkinId") REFERENCES "Checkin" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT "CheckinNoteVersion_editorId_fkey" FOREIGN KEY ("editorId") REFERENCES "Employee" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
)`,o=`CREATE TABLE IF NOT EXISTS "LocationPing" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "employeeId" TEXT NOT NULL,
    "localId" TEXT NOT NULL,
    "lat" REAL NOT NULL,
    "lng" REAL NOT NULL,
    "accuracy" REAL,
    "recordedAt" DATETIME NOT NULL,
    "receivedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "LocationPing_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "Employee" ("id") ON DELETE CASCADE ON UPDATE CASCADE
)`,I=`CREATE TABLE IF NOT EXISTS "TrackStatus" (
    "employeeId" TEXT NOT NULL PRIMARY KEY,
    "locationPermission" TEXT NOT NULL DEFAULT 'unknown',
    "backgroundLocation" TEXT NOT NULL DEFAULT 'unknown',
    "serviceRunning" BOOLEAN NOT NULL DEFAULT false,
    "batteryUnrestricted" BOOLEAN NOT NULL DEFAULT false,
    "appVersion" TEXT,
    "lastReportAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "TrackStatus_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "Employee" ("id") ON DELETE CASCADE ON UPDATE CASCADE
)`;async function d(){let{PrismaClient:d}=await T.A(83700),O=new d,a=0;for(let T of[...E,e,t,'CREATE UNIQUE INDEX IF NOT EXISTS "PlannedVisit_visitId_key" ON "PlannedVisit"("visitId")','ALTER TABLE "AppNotification" ADD COLUMN "eventKey" TEXT','CREATE UNIQUE INDEX IF NOT EXISTS "AppNotification_eventKey_key" ON "AppNotification"("eventKey")',N,'CREATE UNIQUE INDEX IF NOT EXISTS "PanelNotification_eventKey_key" ON "PanelNotification"("eventKey")',L,'CREATE UNIQUE INDEX IF NOT EXISTS "AssignmentRequest_pending_key" ON "AssignmentRequest"("employeeId","customerId") WHERE "status"=\'pending\'',...i,n,'CREATE INDEX IF NOT EXISTS "CheckinNoteVersion_checkinId_idx" ON "CheckinNoteVersion"("checkinId")',o,'CREATE UNIQUE INDEX IF NOT EXISTS "LocationPing_localId_key" ON "LocationPing"("localId")','CREATE INDEX IF NOT EXISTS "LocationPing_employeeId_recordedAt_idx" ON "LocationPing"("employeeId", "recordedAt")',I,'UPDATE "Visit" SET "open" = 0, "endAt" = COALESCE("endAt", "startAt") WHERE "open" = 1 AND "id" NOT IN (  SELECT "id" FROM "Visit" v2 WHERE v2."open" = 1 AND v2."employeeId" = "Visit"."employeeId"   ORDER BY v2."startAt" DESC LIMIT 1)','CREATE UNIQUE INDEX IF NOT EXISTS "Visit_one_open" ON "Visit"("employeeId") WHERE "open" = 1',A,'CREATE INDEX IF NOT EXISTS "OpEvent_employeeId_serverAt_idx" ON "OpEvent"("employeeId", "serverAt")','CREATE INDEX IF NOT EXISTS "OpEvent_type_serverAt_idx" ON "OpEvent"("type", "serverAt")','CREATE INDEX IF NOT EXISTS "OpEvent_serverAt_idx" ON "OpEvent"("serverAt")','CREATE UNIQUE INDEX IF NOT EXISTS "OpEvent_requestId_key" ON "OpEvent"("requestId")'])try{await O.$executeRawUnsafe(T),a++}catch(e){let E=e instanceof Error?e.message:String(e);if(/duplicate column name|already exists/i.test(E))continue;console.error(`[medpad][migrate] FAILED: ${T} -> ${E}`)}try{let T=await O.config.findUnique({where:{key:"trackRetentionDays"}}),E=T?Number(T.value):0/0,e=Number.isFinite(E)&&E>=7&&E<=365?Math.trunc(E):30,t=new Date(Date.now()-24*e*36e5),N=await O.locationPing.deleteMany({where:{receivedAt:{lt:t}}});N.count>0&&console.log(`[medpad][tracking] pruned ${N.count} ping(s) older than ${e} day(s)`)}catch{}try{let T=await O.config.findUnique({where:{key:"eventRetentionDays"}}),E=T?Number(T.value):0/0,e=Number.isFinite(E)&&E>=30&&E<=730?Math.trunc(E):90,t=new Date(Date.now()-24*e*36e5),N=await O.opEvent.deleteMany({where:{serverAt:{lt:t}}});N.count>0&&console.log(`edpad][op-event] pruned ${N.count} event(s) older than ${e} day(s)`)}catch{}await O.$disconnect(),a>0&&console.log(`[medpad][migrate] applied ${a} migration statement(s)`)}T.s(["register",0,d])}];

//# sourceMappingURL=src_instrumentation_ts_07j_8w1ivtswj._.js.map