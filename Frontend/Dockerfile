# syntax=docker/dockerfile:1
FROM nginx:stable

# Drop the stock welcome site; ship only our config.
RUN rm -f /etc/nginx/conf.d/default.conf

COPY nginx/00-ratelimit.conf   /etc/nginx/conf.d/00-ratelimit.conf
COPY nginx/10-nimbus-gui.conf  /etc/nginx/conf.d/10-nimbus-gui.conf
COPY public/                   /usr/share/nginx/html/

# Static assets are read-only to the server process.
RUN chown -R root:root /usr/share/nginx/html \
 && find /usr/share/nginx/html -type f -exec chmod 444 {} + \
 && find /usr/share/nginx/html -type d -exec chmod 555 {} +

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD nginx -t >/dev/null 2>&1 || exit 1
