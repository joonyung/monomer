/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_MONOMER_CONTROL_API_URL: string;
  readonly VITE_MONOMER_PUBLIC_SITE_ORIGIN?: string;
  readonly VITE_LOCAL_LOGIN_ENABLED?: string;
  readonly VITE_GOOGLE_OAUTH_ENABLED?: string;
  readonly VITE_E2E_AUTH_BYPASS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
