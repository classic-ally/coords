import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  site: 'https://coord.is',

  image: {
    // High quality, minimal compression
    service: {
      config: {
        quality: 90,
      },
    },
  },

  integrations: [
    starlight({
      title: 'Coords',
      description: 'Private location sharing',
      customCss: ['./src/styles/global.css'],
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/classic-ally' },
      ],
      sidebar: [
        {
          label: 'Docs',
          items: [
            { label: 'Introduction', slug: 'docs/intro' },
            { label: 'Self-Hosting', slug: 'docs/self-hosting' },
          ],
        },
      ],
    }),
  ],

  vite: {
    plugins: [tailwindcss()],
  },
});