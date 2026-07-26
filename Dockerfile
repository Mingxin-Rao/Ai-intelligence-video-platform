# Use python image for M1 chips
FROM python:3.10-slim

# Upload necessary tools for AI video
RUN apt-get update && apt-get install -y \
    ffmpeg \
    libsm6 \
    libxext6 \
    && rm -rf /var/lib/apt/lists/*

# Set work direction
WORKDIR /app


# Start and detect python version
CMD ["python", "--version"]