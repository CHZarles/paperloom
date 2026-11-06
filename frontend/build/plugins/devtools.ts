import VueDevtools from 'vite-plugin-vue-devtools';

/**
 * Mount the Vue DevTools floating panel.
 *
 * It injects an overlay into every dev page, which is useful for component work and annoying while tuning layout. Off
 * by default; set `VITE_DEVTOOLS=Y` in `.env` to bring the panel back.
 */
export function setupDevtoolsPlugin(viteEnv: Env.ImportMeta) {
  const { VITE_DEVTOOLS_LAUNCH_EDITOR, VITE_DEVTOOLS } = viteEnv;

  if (VITE_DEVTOOLS !== 'Y') {
    return null;
  }

  return VueDevtools({
    launchEditor: VITE_DEVTOOLS_LAUNCH_EDITOR
  });
}
