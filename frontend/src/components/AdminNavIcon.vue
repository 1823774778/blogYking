<template>
  <svg v-if="name === 'dashboard'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <rect x="3" y="3" width="7" height="7" rx="1.5" />
    <rect x="14" y="3" width="7" height="7" rx="1.5" />
    <rect x="3" y="14" width="7" height="7" rx="1.5" />
    <rect x="14" y="14" width="7" height="7" rx="1.5" />
  </svg>
  <svg v-else-if="name === 'profile'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <circle cx="12" cy="8" r="3.4" />
    <path d="M5 20a7 7 0 0 1 14 0" />
  </svg>
  <svg v-else-if="name === 'users'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <circle cx="9" cy="8" r="3" />
    <path d="M3.5 19a5.5 5.5 0 0 1 11 0M16 5.5a3 3 0 0 1 0 5.6M17 14.5a5 5 0 0 1 3.5 4.5" />
  </svg>
  <svg v-else-if="name === 'roles'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <path d="M12 3 4.5 6v5.3c0 4.7 3.1 8.2 7.5 9.7 4.4-1.5 7.5-5 7.5-9.7V6L12 3Z" />
    <circle cx="9.5" cy="11" r="2" />
    <path d="M5.8 16a3.7 3.7 0 0 1 7.4 0M15 10h2.5M15 13h2.5" />
  </svg>
  <svg v-else-if="name === 'posts'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <path d="M5 3h10l4 4v14H5V3Z" />
    <path d="M15 3v4h4M8 12h8M8 16h6" />
  </svg>
  <svg v-else-if="name === 'comments'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <path d="M4 5h16v11H9l-5 4V5Z" />
    <path d="M8 9h8M8 12h5" />
  </svg>
  <svg v-else-if="name === 'tags'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <path d="M20 13 13 20a2 2 0 0 1-2.8 0L4 13.8V4h9.8L20 10.2a2 2 0 0 1 0 2.8Z" />
    <circle cx="8.5" cy="8.5" r="1.4" />
  </svg>
  <svg v-else-if="name === 'announcements'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <path d="M4 10v4l12 5V5L4 10Z" />
    <path d="M8 15v4h4v-3M18 8a4 4 0 0 1 0 8" />
  </svg>
  <svg v-else-if="name === 'themes'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <circle cx="12" cy="12" r="9" />
    <path d="M12 3a9 9 0 0 0 0 18c2 0 2-1 2-2 0-1.5 1-2 2-2h2a3 3 0 0 0 3-3 9 9 0 0 0-9-11Z" />
    <circle cx="8" cy="9" r="1" /><circle cx="12" cy="7" r="1" /><circle cx="16" cy="9" r="1" />
  </svg>
  <svg v-else-if="name === 'site'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <circle cx="12" cy="12" r="9" />
    <path d="M3 12h18M12 3a15 15 0 0 1 0 18M12 3a15 15 0 0 0 0 18" />
  </svg>
  <svg v-else-if="name === 'storage'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <ellipse cx="12" cy="6" rx="8" ry="3" />
    <path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" />
  </svg>
  <svg v-else-if="name === 'navigation'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <path d="M4 6h16M4 12h10M4 18h6" />
    <path d="m18 14 3 3-3 3" />
  </svg>
  <svg v-else-if="name === 'notifications'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
    <path d="M10 21h4" />
  </svg>
  <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-1.9 1.9-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V20h-2.6v-.1A1.7 1.7 0 0 0 11 18.4a1.7 1.7 0 0 0-1.9.3l-.1.1-1.9-1.9.1-.1A1.7 1.7 0 0 0 7.5 15a1.7 1.7 0 0 0-1.5-1H6v-2.6h.1A1.7 1.7 0 0 0 7.5 10a1.7 1.7 0 0 0-.3-1.9L7.1 8l1.9-1.9.1.1A1.7 1.7 0 0 0 11 6.5a1.7 1.7 0 0 0 1-1.5V5h2.6v.1A1.7 1.7 0 0 0 15.6 6.5a1.7 1.7 0 0 0 1.9-.3l.1-.1L19.5 8l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.5 1H21v2.6h-.1a1.7 1.7 0 0 0-1.5 1Z" />
  </svg>
</template>

<script setup>
defineProps({ name: { type: String, default: 'settings' } })
</script>
