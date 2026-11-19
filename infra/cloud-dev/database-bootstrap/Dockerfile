FROM postgres:17.10-bookworm@sha256:9b18b78397054fce88a9552e9d5a3ad5bb7fd258c5b3cc1c5028e46373d6ea8f
COPY . /bootstrap/
USER 65532:65532
ENTRYPOINT ["/bin/sh", "/bootstrap/run.sh"]
