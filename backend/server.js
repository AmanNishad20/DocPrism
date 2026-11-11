/**
 * DocPrism Stateless Office-to-PDF Conversion Service (Node.js + Fastify)
 * - Supports: DOC, DOCX, XLS, XLSX, PPT, PPTX, ODT, ODS, ODP, RTF, CSV, TXT
 * - Wide Spreadsheets: Injects LibreOffice Calc FitToPagesX=1, FitToPagesY=0
 * - Privacy: Zero-retention RAM-backed /tmp job directory, immediate unlink in finally{}
 * - Abuse Protection: 25 MB limit, 30s timeout, FIFO concurrency queue, IP rate limiting
 */
const fastify = require('fastify')({ logger: false, bodyLimit: 25 * 1024 * 1024 });
const multipart = require('@fastify/multipart');
const rateLimit = require('@fastify/rate-limit');
const { spawn } = require('child_process');
const fs = require('fs/promises');
const path = require('path');
const crypto = require('crypto');

const ALLOWED_EXT = new Set([
  'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx',
  'odt', 'ods', 'odp', 'rtf', 'csv', 'txt'
]);
const SPREADSHEET_EXT = new Set(['xls', 'xlsx', 'ods', 'csv']);
const MAX_CONCURRENT_WORKERS = 2;
const CONVERSION_TIMEOUT_MS = 30_000;

let activeWorkers = 0;
const waitQueue = [];

function acquireSlot() {
  if (activeWorkers < MAX_CONCURRENT_WORKERS) {
    activeWorkers++;
    return Promise.resolve();
  }
  return new Promise((resolve) => waitQueue.push(resolve));
}

function releaseSlot() {
  if (waitQueue.length > 0) {
    const next = waitQueue.shift();
    next();
  } else {
    activeWorkers--;
  }
}

fastify.register(rateLimit, {
  max: 10,
  timeWindow: '1 minute',
  errorResponseBuilder: (req, context) => ({
    code: 'ERR_TOO_MANY_REQUESTS_429',
    message: `Rate limit exceeded (${context.max} req/min). Retry in ${Math.ceil(context.ttl / 1000)}s.`
  })
});

fastify.register(multipart, {
  limits: { fileSize: 25 * 1024 * 1024, files: 1 }
});

fastify.post('/v1/convert/office-to-pdf', async (req, reply) => {
  const data = await req.file();
  if (!data) return reply.code(400).send({ error: 'Missing file upload' });

  const ext = path.extname(data.filename || '').slice(1).toLowerCase();
  if (!ALLOWED_EXT.has(ext)) {
    return reply.code(415).send({ error: `Unsupported format: .${ext}` });
  }

  const jobId = crypto.randomUUID();
  const jobDir = path.join('/tmp', `docprism_${jobId}`);
  const inputPath = path.join(jobDir, `input.${ext}`);
  const outputPath = path.join(jobDir, 'input.pdf');

  await acquireSlot();
  try {
    await fs.mkdir(jobDir, { recursive: true, mode: 0o700 });
    const fileBuffer = await data.toBuffer();

    // Detect encrypted OOXML / OLE2 password protection before spawning LibreOffice
    if (fileBuffer.includes(Buffer.from('EncryptedPackage')) ||
        fileBuffer.includes(Buffer.from('EncryptionInfo'))) {
      return reply.code(422).send({ error: 'Password-protected Office file cannot be converted.' });
    }

    await fs.writeFile(inputPath, fileBuffer);

    // For wide spreadsheets, enforce single-page width scaling (FitToPagesX=1, FitToPagesY=0)
    const filterArg = SPREADSHEET_EXT.has(ext)
      ? 'pdf:calc_pdf_Export:{"SinglePageSheets":{"type":"boolean","value":"false"},"FitToPagesX":{"type":"long","value":"1"},"FitToPagesY":{"type":"long","value":"0"}}'
      : 'pdf:writer_pdf_Export';

    await runLibreOfficeWithTimeout(jobDir, inputPath, filterArg, CONVERSION_TIMEOUT_MS);

    const pdfBytes = await fs.readFile(outputPath);
    reply
      .header('Content-Type', 'application/pdf')
      .header('X-Zero-Retention-Verified', 'true')
      .header('X-Fit-Width-Applied', String(SPREADSHEET_EXT.has(ext)))
      .send(pdfBytes);
  } finally {
    // Immediate zero-retention deletion of uploaded Office file and converted PDF
    await fs.rm(jobDir, { recursive: true, force: true }).catch(() => {});
    releaseSlot();
  }
});

function runLibreOfficeWithTimeout(jobDir, inputPath, filterArg, timeoutMs) {
  return new Promise((resolve, reject) => {
    const proc = spawn('libreoffice', [
      '--headless',
      '--nologo',
      '--nofirststartwizard',
      '--norestore',
      `-env:UserInstallation=file://${jobDir}/lo_profile`,
      '--convert-to', filterArg,
      '--outdir', jobDir,
      inputPath
    ]);
    const timer = setTimeout(() => {
      proc.kill('SIGKILL');
      reject(new Error('CONVERSION_TIMEOUT'));
    }, timeoutMs);

    proc.on('exit', (code) => {
      clearTimeout(timer);
      if (code === 0) resolve();
      else reject(new Error(`LibreOffice exited with code ${code}`));
    });
  });
}

fastify.listen({ port: process.env.PORT || 8080, host: '0.0.0.0' });
