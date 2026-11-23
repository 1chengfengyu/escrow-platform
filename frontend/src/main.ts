import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { setForbiddenHandler, setUnauthorizedHandler } from './api/http'
import { useAuthStore } from './stores/auth'
import './styles/base.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

setUnauthorizedHandler(() => {
  const auth = useAuthStore(pinia)
  auth.logout()
  const current = router.currentRoute.value
  if (current.path !== '/login') {
    void router.push({ path: '/login', query: { redirect: current.fullPath } })
  }
})

setForbiddenHandler(() => {
  if (router.currentRoute.value.path !== '/403') {
    void router.push('/403')
  }
})

app.mount('#app')
