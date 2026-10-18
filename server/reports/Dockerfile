FROM python:3.12-alpine
RUN adduser -D -H -u 10001 reports && mkdir /data && chown reports /data
COPY reports.py /app/reports.py
USER reports
ENV PYTHONUNBUFFERED=1 DATA_DIR=/data PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
CMD ["python", "/app/reports.py"]
