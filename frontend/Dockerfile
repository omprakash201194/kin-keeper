# Expects a pre-built dist/ in frontend/dist/ (built by Vite before docker build).
# CI builds dist/ on a GitHub-hosted runner with VITE_FIREBASE_* secrets injected,
# then passes it here. Local builds: run `npm run build` first, then `docker build`.

FROM nginx:1.27-alpine
COPY dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
