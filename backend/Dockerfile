# ============================================================================
# DocPrism Stateless Zero-Retention Office-to-PDF Conversion Container
# Free-Tier Ready: Google Cloud Run / Oracle Always-Free ARM / Fly.io / Render
# Includes full Indic (Hindi/Bengali/Tamil), Arabic (RTL), and CJK Font Packs
# ============================================================================
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    NODE_ENV=production \
    PORT=8080

# Install Headless LibreOffice + HarfBuzz + Comprehensive Multilingual Fonts
RUN apt-get update && apt-get install -y --no-install-recommends \
    libreoffice-writer \
    libreoffice-calc \
    libreoffice-impress \
    libreoffice-common \
    python3-uno \
    nodejs \
    npm \
    fontconfig \
    # Latin & Microsoft Core Metric-Compatible Fonts (Carlito=Calibri, Caladea=Cambria)
    fonts-liberation2 \
    fonts-crosextra-carlito \
    fonts-crosextra-caladea \
    fonts-dejavu-core \
    # Indic Scripts: Hindi (Devanagari), Bengali, Tamil, Telugu, Gujarati, Kannada, Malayalam, Gurmukhi
    fonts-noto-core \
    fonts-noto-extra \
    fonts-indic \
    fonts-lohit-deva \
    # Arabic & Persian Right-to-Left (RTL) Scripts
    fonts-hosny-amiri \
    fonts-kacst \
    # East Asian CJK Scripts (Simplified/Traditional Chinese, Japanese, Korean)
    fonts-noto-cjk \
    && fc-cache -fv \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package.json server.js ./
RUN npm install --omit=dev

# Run as non-root user with tmpfs ephemeral scratch space
USER nobody
EXPOSE 8080
CMD ["node", "server.js"]
