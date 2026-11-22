<template>
  <!--
    Plain HTML on purpose, no Vuetify: this page also shows when a plugin fails at startup
    (for example the API is unreachable), and Vuetify may not be installed by then.
  -->
  <main
    v-if="ready"
    class="error-page"
  >
    <h1>{{ notFound ? $t("page.404-page-not-found") : $t("page.an-error-occurred") }}</h1>
    <div
      class="error-art"
      aria-hidden="true"
    >
      <span v-if="notFound">4</span>
      <svg
        viewBox="0 0 24 24"
        class="error-icon"
      >
        <path :d="icons.primary" />
      </svg>
      <span v-if="notFound">4</span>
    </div>
    <p
      v-if="!notFound && details"
      class="error-details"
    >
      {{ details }}
    </p>
    <div class="error-actions">
      <button
        v-for="(button, index) in buttons"
        :key="index"
        type="button"
        class="error-button"
        @click="goTo(button.to)"
      >
        <svg
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path :d="button.icon" />
        </svg>
        {{ button.text }}
      </button>
    </div>
  </main>
</template>

<script setup lang="ts">
import { useGlobalI18n } from "~/composables/use-global-i18n";
// Imported directly, not from $globals: when a plugin fails at startup (for example the API is
// unreachable), this page renders before the globals plugin has run.
import { icons } from "~/lib/icons";

const props = defineProps({
  error: {
    type: Object,
    default: null,
  },
});

const i18n = useGlobalI18n();
const auth = useMealieAuth();
const ready = ref(false);

const notFound = computed(() => props.error?.statusCode === 404);
const details = computed(() => {
  const status = props.error?.statusCode;
  const message = props.error?.statusMessage || props.error?.message;
  return [status, message].filter(Boolean).join(" · ");
});

/**
 * A 404 is a missing route, so clearing the error is enough. Any other error may come from a
 * plugin that failed at startup, and clearError does not run plugins again: reload the page.
 */
async function goTo(to: string) {
  if (notFound.value) {
    await clearError({ redirect: to });
  }
  else {
    window.location.assign(to);
  }
}

const route = useRoute();
const router = useRouter();

async function insertGroupSlugIntoRoute() {
  const groupSlug = ref(auth.user.value?.groupSlug);
  if (!groupSlug.value) {
    return;
  }

  let replaceRoute = false;
  let routeVal = route.fullPath || "/";
  if (routeVal[0] !== "/") {
    routeVal = `/${routeVal}`;
  }

  // replace "recipe" in URL with "r"
  if (routeVal.includes("/recipe/")) {
    replaceRoute = true;
    routeVal = routeVal.replace("/recipe/", "/r/");
  }

  // insert groupSlug into URL
  const routeComponents = routeVal.split("/");
  if (routeComponents.length < 2 || routeComponents[1].toLowerCase() !== "g") {
    replaceRoute = true;
    routeVal = `/g/${groupSlug.value}${routeVal}`;
  }

  // Only leave the error page for a path that exists: an unknown path under
  // /g/<groupSlug> matches only the group page and would show an empty page
  if (replaceRoute && router.resolve(routeVal).name) {
    await clearError({ redirect: routeVal });
  }
}

async function handle404() {
  const normalizedRoute = route.fullPath.replace(/\/$/, "");
  const newRoute = normalizedRoute.replace(/^\/group\/(mealplan|members|notifiers|webhooks)(\/.*)?$/, "/household/$1$2");

  if (newRoute !== normalizedRoute) {
    await clearError({ redirect: newRoute });
  }
  else {
    await insertGroupSlugIntoRoute();
  }

  ready.value = true;
}

if (notFound.value) {
  handle404();
}
else {
  ready.value = true;
}

useSeoMeta({
  title:
        notFound.value
          ? (i18n.t("page.404-not-found") as string)
          : (i18n.t("page.an-error-occurred") as string),
});

const buttons = [
  { icon: icons.home, to: "/", text: i18n.t("general.home") },
];
</script>

<style scoped>
/* Vuetify's theme variables when Vuetify is up, Mealie's defaults when it is not. */
.error-page {
  --error-primary: rgb(var(--v-theme-primary, 229, 131, 37));
  --error-on-primary: rgb(var(--v-theme-on-primary, 255, 255, 255));
  --error-surface: rgb(var(--v-theme-background, 255, 255, 255));
  --error-on-surface: rgb(var(--v-theme-on-background, 33, 33, 33));

  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 24px;
  box-sizing: border-box;
  background: var(--error-surface);
  color: var(--error-on-surface);
  font-family: Roboto, system-ui, sans-serif;
  text-align: center;
}

@media (prefers-color-scheme: dark) {
  .error-page {
    --error-surface: rgb(var(--v-theme-background, 18, 18, 18));
    --error-on-surface: rgb(var(--v-theme-on-background, 238, 238, 238));
  }
}

h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 500;
}

.error-art {
  display: flex;
  align-items: center;
  color: var(--error-primary);
  font-size: clamp(96px, 25vw, 200px);
  line-height: 1;
}

.error-icon {
  width: 1em;
  height: 1em;
  fill: currentColor;
}

.error-details {
  margin: 0;
  opacity: 0.7;
  overflow-wrap: anywhere;
}

.error-actions {
  display: flex;
  gap: 8px;
}

.error-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 0 20px;
  border: 0;
  border-radius: 20px;
  background: var(--error-primary);
  color: var(--error-on-primary);
  font: inherit;
  font-weight: 500;
  cursor: pointer;
}

.error-button:focus-visible {
  outline: 2px solid var(--error-primary);
  outline-offset: 2px;
}

.error-button svg {
  width: 18px;
  height: 18px;
  fill: currentColor;
}
</style>
