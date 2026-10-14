FROM python:3.11-slim

WORKDIR /app
COPY sample_app/requirements.txt /tmp/requirements.txt
RUN python -m pip install --no-cache-dir -r /tmp/requirements.txt
COPY sample_app/shoplite /app/shoplite
CMD ["sh", "-c", "gunicorn --bind 0.0.0.0:${PORT:-8080} --workers 2 'shoplite:create_app()'"]
