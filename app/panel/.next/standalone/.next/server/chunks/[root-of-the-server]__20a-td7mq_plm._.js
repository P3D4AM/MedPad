module.exports=[83700,e=>{e.v(T=>Promise.all(["server/chunks/[externals]_@prisma_client_1ltre0sawm6cs._.js"].map(T=>e.l(T))).then(()=>T(63021)))},23322,e=>{"use strict";let T=['ALTER TABLE "AppToken" ADD COLUMN "expiresAt" DATETIME','ALTER TABLE "AppToken" ADD COLUMN "revokedAt" DATETIME','ALTER TABLE "Checkin" ADD COLUMN "accuracy" REAL','ALTER TABLE "Checkin" ADD COLUMN "distanceM" REAL','ALTER TABLE "Checkin" ADD COLUMN "outOfRadius" BOOLEAN NOT NULL DEFAULT 0','ALTER TABLE "Checkin" ADD COLUMN "locationMissing" BOOLEAN NOT NULL DEFAULT 0'],t=`CREATE TABLE IF NOT EXISTS "AuditLog" (
  "id" TEXT NOT NULL PRIMARY KEY,
  "actorType" TEXT NOT NULL DEFAULT 'admin',
  "actorId" TEXT NOT NULL,
  "action" TEXT NOT NULL,
  "targetType" TEXT,
  "targetId" TEXT,
  "detail" TEXT,
  "outcome" TEXT NOT NULL DEFAULT 'success',
  "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`;async function L(){let{PrismaClient:L}=await e.A(83700),E=new L,A=0;for(let e of[...T,t])try{await E.$executeRawUnsafe(e),A++}catch(t){let T=t instanceof Error?t.message:String(t);if(/duplicate column name|already exists/i.test(T))continue;console.error(`[medpad][migrate] FAILED: ${e} -> ${T}`)}await E.$disconnect(),A>0&&console.log(`[medpad][migrate] applied ${A} migration statement(s)`)}e.s(["register",0,L])}];

//# sourceMappingURL=%5Broot-of-the-server%5D__20a-td7mq_plm._.js.map