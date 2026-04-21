FROM alpine:3.19
RUN apk add --no-cache bash curl jq
COPY wf.sh /usr/local/bin/wf
RUN chmod +x /usr/local/bin/wf
ENTRYPOINT ["wf"]
