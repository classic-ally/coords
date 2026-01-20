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
      components: {
        Hero: './src/components/Hero.astro',
      },
      social: [
        { icon: 'open-book', label: 'Docs', href: '/docs/intro' },
        { icon: 'seti:git', label: 'Source', href: 'https://tangled.org/bentley.sh/coords' },
      ],
      sidebar: [
        {
          label: 'Docs',
          items: [
            { label: 'Introduction', slug: 'docs/intro' },
            { label: 'Self-Hosting', slug: 'docs/self-hosting' },
          ],
        },
        {
          label: 'Developers',
          items: [
            { label: 'Protocol', slug: 'docs/protocol' },
            { label: 'API Reference', slug: 'docs/api' },
            { label: 'Client Architecture', slug: 'docs/clients' },
          ],
        },
      ],
    }),
  ],

  vite: {
    plugins: [tailwindcss()],
  },
});