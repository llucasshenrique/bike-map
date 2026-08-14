import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.ebike.router',
  appName: 'E-Bike Navigator',
  webDir: 'dist/apps/bike-map/browser',
  server: {
    androidScheme: 'https'
  },
  android: {
    allowMixedContent: true,
    captureInput: true,
    backgroundColor: '#0f172a'
  }
};

export default config;
