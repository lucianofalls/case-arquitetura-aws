FROM python:3.12-slim
WORKDIR /tools
COPY tools/localstack/requirements.txt /tools/requirements.txt
RUN pip install --no-cache-dir -r /tools/requirements.txt
COPY tools/localstack/*.py /tools/
USER 65534:65534
