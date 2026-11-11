package com.example.data

data class HostingProviderOption(
    val name: String,
    val specs: String,
    val freeAllowance: String,
    val monthlyCapacityEstimate: String,
    val pros: String,
    val stayFreeStrategy: String
)

data class BlueprintCodeSnippet(
    val id: String,
    val title: String,
    val subtitle: String,
    val language: String,
    val code: String
)

object BackendBlueprintCatalog {

    val freeHostingOptions = listOf(
        HostingProviderOption(
            name = "1. Google Cloud Run (Recommended Serverless)",
            specs = "1 vCPU • 1 GB RAM • Scale-to-Zero (min-instances=0)",
            freeAllowance = "2M requests + 180,000 vCPU-sec + 360,000 GiB-sec / month free",
            monthlyCapacityEstimate = "~180,000 Office-to-PDF conversions/month at $0.00",
            pros = "Automatic TLS 1.3, scales to $0 when idle, burst concurrency, global CDN edge.",
            stayFreeStrategy = "Set --max-instances=2, --concurrency=2, --memory=1Gi, and rely on mobile SHA-256 caching so repeat opens never invoke Cloud Run."
        ),
        HostingProviderOption(
            name = "2. Oracle Cloud Free Tier (Always-Free ARM VM)",
            specs = "4 Ampere A1 ARM Cores • 24 GB RAM • 200 GB Block Storage",
            freeAllowance = "3,000 OCPU hours + 18,000 GB hours / month (100% Free Forever)",
            monthlyCapacityEstimate = "500,000+ conversions/month with zero cold-start latency",
            pros = "Massive 24 GB RAM runs 4 parallel LibreOffice workers with 0ms cold start.",
            stayFreeStrategy = "Mount /tmp as a 4 GB tmpfs RAM disk so uploads and PDFs never touch SSD and vanish on unlink."
        ),
        HostingProviderOption(
            name = "3. Fly.io / Render Free Web Service",
            specs = "Shared CPU • 512 MB - 1 GB RAM • Docker Container",
            freeAllowance = "Render: 750 free hrs/mo • Fly.io: auto-stop/start machines",
            monthlyCapacityEstimate = "~45,000 conversions/month for lightweight teams",
            pros = "Deploy directly from GitHub Dockerfile in 2 minutes.",
            stayFreeStrategy = "Enforce MAX_CONCURRENT_WORKERS=1 on 512MB RAM instances to prevent OOM kills on large spreadsheets."
        )
    )

    val codeSnippets = listOf(
        BlueprintCodeSnippet(
            id = "dockerfile",
            title = "Dockerfile (LibreOffice + Indic/Arabic/CJK Fonts)",
            subtitle = "Debian Bookworm Slim + HarfBuzz + Noto Indic/Arabic/CJK + Metric Fonts",
            language = "dockerfile",
            code = """
FROM debian:bookworm-slim
ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8

RUN apt-get update && apt-get install -y --no-install-recommends \
    libreoffice-writer libreoffice-calc libreoffice-impress \
    python3-uno nodejs npm fontconfig \
    fonts-liberation2 fonts-crosextra-carlito fonts-crosextra-caladea \
    fonts-noto-core fonts-noto-extra fonts-indic fonts-lohit-deva \
    fonts-hosny-amiri fonts-noto-cjk \
    && fc-cache -fv && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package.json server.js ./
RUN npm install --omit=dev
USER nobody
EXPOSE 8080
CMD ["node", "server.js"]
            """.trimIndent()
        ),
        BlueprintCodeSnippet(
            id = "server_js",
            title = "server.js (Zero-Retention + Calc Fit-to-Width + Queue)",
            subtitle = "Fastify multipart endpoint with immediate finally{} file wipe & rate limiter",
            language = "javascript",
            code = """
// Enforces 25MB limit, 10 req/min rate limit, 2-worker FIFO queue, and 30s timeout
const filterArg = SPREADSHEET_EXT.has(ext)
  ? 'pdf:calc_pdf_Export:{"FitToPagesX":{"type":"long","value":"1"},"FitToPagesY":{"type":"long","value":"0"}}'
  : 'pdf:writer_pdf_Export';

try {
  await fs.writeFile(inputPath, fileBuffer);
  await runLibreOfficeWithTimeout(jobDir, inputPath, filterArg, 30000);
  const pdfBytes = await fs.readFile(outputPath);
  reply.header('Content-Type', 'application/pdf').send(pdfBytes);
} finally {
  // PRIVACY GUARANTEE: Immediately delete uploaded Office file & converted PDF
  await fs.rm(jobDir, { recursive: true, force: true });
  releaseQueueSlot();
}
            """.trimIndent()
        ),
        BlueprintCodeSnippet(
            id = "ios_swift",
            title = "OfficePdfViewerModule.swift (iOS SwiftUI + PDFKit)",
            subtitle = "Cross-platform iOS counterpart using CryptoKit SHA-256 + Apple PDFKit",
            language = "swift",
            code = """
public struct NativePDFKitRepresentable: UIViewRepresentable {
    public let pdfURL: URL
    public func makeUIView(context: Context) -> PDFView {
        let pdfView = PDFView()
        pdfView.autoScales = true
        pdfView.displayMode = .singlePageContinuous
        pdfView.document = PDFDocument(url: pdfURL)
        return pdfView
    }
    public func updateUIView(_ uiView: PDFView, context: Context) {
        if uiView.document?.documentURL != pdfURL {
            uiView.document = PDFDocument(url: pdfURL)
        }
    }
}
            """.trimIndent()
        )
    )
}
