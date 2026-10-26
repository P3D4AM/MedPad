module.exports=[83700,e=>{e.v(t=>Promise.all(["server/chunks/[externals]_@prisma_client_1ltre0sawm6cs._.js"].map(t=>e.l(t))).then(()=>t(63021)))},23322,e=>{"use strict";let t=['ALTER TABLE "AppToken" ADD COLUMN "expiresAt" DATETIME','ALTER TABLE "AppToken" ADD COLUMN "revokedAt" DATETIME'],T=`CREATE TABLE IF NOT EXISTS "AuditLog" (
  "id" TEXT NOT NULL PRIMARY KEY,
  "actorType" TEXT NOT NULL DEFAULT 'admin',
  "actorId" TEXT NOT NULL,
  "action" TEXT NOT NULL,
  "targetType" TEXT,
  "targetId" TEXT,
  "detail" TEXT,
  "outcome" TEXT NOT NULL DEFAULT 'success',
  "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`;async function a(){let{PrismaClient:a}=await e.A(83700),r=new a,s=0;for(let e of[...t,T])try{await r.$executeRawUnsafe(e),s++}catch(T){let t=T instanceof Error?T.message:String(T);if(/duplicate column name|already exists/i.test(t))continue;console.error(`[medpad][migrate] FAILED: ${e} -> ${t}`)}await r.$disconnect(),s>0&&console.log(`[medpad][migrate] applied ${s} migration statement(s)`)}e.s(["register",0,a])}];

//# sourceMappingURL=%5Broot-of-the-server%5D__20a-td7mq_plm._.js.map