# SignalBot Dockerfile
FROM python:3.11-slim

# Set working directory
WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements first for better caching
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY src/ ./src/
COPY main.py .
COPY config.example.yaml .

# Create directory for data
RUN mkdir -p /data

# Set environment variables
ENV SIGNALBOT_CONFIG=/data/config.yaml \
    SIGNALBOT_STORE=/data/messaged.json \
    SIGNALBOT_METRICS=/data/metrics.json \
    PYTHONUNBUFFERED=1

# Health check
HEALTHCHECK --interval=60s --timeout=10s --start-period=30s --retries=3 \
    CMD python -c "import os; exit(0 if os.path.exists('/data/metrics.json') else 1)"

# Run as non-root user
RUN useradd -m -u 1000 signalbot && \
    chown -R signalbot:signalbot /app /data
USER signalbot

# Default command
CMD ["python", "main.py"]
