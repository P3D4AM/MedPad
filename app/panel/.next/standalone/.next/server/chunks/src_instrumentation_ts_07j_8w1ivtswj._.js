module.exports=[23322,T=>{"use strict";let E=['ALTER TABLE "AppToken" ADD COLUMN "expiresAt" DATETIME','ALTER TABLE "AppToken" ADD COLUMN "revokedAt" DATETIME','ALTER TABLE "Checkin" ADD COLUMN "accuracy" REAL','ALTER TABLE "Checkin" ADD COLUMN "distanceM" REAL','ALTER TABLE "Checkin" ADD COLUMN "outOfRadius" BOOLEAN NOT NULL DEFAULT 0','ALTER TABLE "Checkin" ADD COLUMN "locationMissing" BOOLEAN NOT NULL DEFAULT 0'],e=`CREATE TABLE IF NOT EXISTS "AuditLog" (
  "id" TEXT NOT NULL PRIMARY KEY,
  "actorType" TEXT NOT NULL DEFAULT 'admin',
  "actorId" TEXT NOT NULL,
  "action" TEXT NOT NULL,
  "targetType" TEXT,
  "targetId" TEXT,
  "detail" TEXT,
  "outcome" TEXT NOT NULL DEFAULT 'success',
  "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`,N=`CREATE TABLE IF NOT EXISTS "PlannedVisit" (
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
)`,t=`CREATE TABLE IF NOT EXISTS "PanelNotification" (
  "id" TEXT NOT NULL PRIMARY KEY,
  "eventKey" TEXT NOT NULL,
  "kind" TEXT NOT NULL DEFAULT 'info',
  "title" TEXT NOT NULL,
  "body" TEXT NOT NULL,
  "employeeId" TEXT,
  "customerId" TEXT,
  "readAt" DATETIME,
  "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
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
)`;async function A(){let{PrismaClient:A}=await T.A(83700),i=new A,n=0;for(let T of[...E,e,N,'CREATE UNIQUE INDEX IF NOT EXISTS "PlannedVisit_visitId_key" ON "PlannedVisit"("visitId")','ALTER TABLE "AppNotification" ADD COLUMN "eventKey" TEXT','CREATE UNIQUE INDEX IF NOT EXISTS "AppNotification_eventKey_key" ON "AppNotification"("eventKey")',t,'CREATE UNIQUE INDEX IF NOT EXISTS "PanelNotification_eventKey_key" ON "PanelNotification"("eventKey")',L,'CREATE UNIQUE INDEX IF NOT EXISTS "AssignmentRequest_pending_key" ON "AssignmentRequest"("employeeId","customerId") WHERE "status"=\'pending\''])try{await i.$executeRawUnsafe(T),n++}catch(e){let E=e instanceof Error?e.message:String(e);if(/duplicate column name|already exists/i.test(E))continue;console.error(`[medpad][migrate] FAILED: ${T} -> ${E}`)}await i.$disconnect(),n>0&&console.log(`[medpad][migrate] applied ${n} migration statement(s)`)}T.s(["register",0,A])}];

//# sourceMappingURL=src_instrumentation_ts_07j_8w1ivtswj._.js.map