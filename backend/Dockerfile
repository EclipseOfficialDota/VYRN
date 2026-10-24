FROM golang:1.27.2-alpine@sha256:f92b6ef800e499660581efdabdf25d9d817a9d124eaf900924f0504e7e27e12d AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /vyrn ./cmd/vyrn
FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8
RUN apk add --no-cache ca-certificates tzdata wget && addgroup -g 10001 vyrn && adduser -D -u 10001 -G vyrn vyrn && mkdir -p /media && chown vyrn:vyrn /media
COPY --from=build /vyrn /usr/local/bin/vyrn
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/vyrn"]
