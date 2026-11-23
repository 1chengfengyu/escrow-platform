import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    title?: string
    eyebrow?: string
    section?: 'public' | 'worker' | 'employer' | 'admin'
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/PublicLayout.vue'),
    children: [
      { path: '', name: 'home', component: () => import('@/views/p/Home.vue'), meta: { public: true, title: '首页', eyebrow: 'FLEXIBLE WORK' } },
      { path: 'tasks', name: 'task-market', component: () => import('@/views/p/TaskMarket.vue'), meta: { public: true, title: '任务广场', eyebrow: 'MARKET' } },
      { path: 'tasks/:id', name: 'public-task-detail', component: () => import('@/views/p/PublicTaskDetail.vue'), meta: { public: true, title: '任务详情', eyebrow: 'TASK' } },
      { path: 'login', name: 'login', component: () => import('@/views/p/Login.vue'), meta: { public: true, title: '登录', eyebrow: 'SIGN IN' } },
      { path: 'register', name: 'register', component: () => import('@/views/p/Register.vue'), meta: { public: true, title: '注册', eyebrow: 'JOIN' } },
      { path: 'how-it-works', name: 'how-it-works', component: () => import('@/views/p/HowItWorks.vue'), meta: { public: true, title: '如何运作', eyebrow: 'HOW IT WORKS' } },
      { path: 'help', name: 'help', component: () => import('@/views/p/HelpCenter.vue'), meta: { public: true, title: '帮助中心', eyebrow: 'HELP' } },
      { path: 'help/account-recovery', name: 'account-recovery', component: () => import('@/views/p/AccountRecovery.vue'), meta: { public: true, title: '账号找回', eyebrow: 'RECOVERY' } },
      { path: 'workers/:id', name: 'worker-profile', component: () => import('@/views/p/WorkerProfile.vue'), meta: { public: true, title: '服务者名片', eyebrow: 'PROFILE' } },
      { path: '403', name: 'forbidden', component: () => import('@/views/p/errors/Forbidden.vue'), meta: { public: true, title: '无访问权限', eyebrow: '403' } },
      { path: '404', name: 'not-found', component: () => import('@/views/p/errors/NotFound.vue'), meta: { public: true, title: '页面不存在', eyebrow: '404' } },
      { path: 'session-expired', name: 'session-expired', component: () => import('@/views/p/errors/SessionExpired.vue'), meta: { public: true, title: '登录已失效', eyebrow: 'SESSION' } },
    ],
  },
  {
    path: '/worker',
    component: () => import('@/layouts/PortalLayout.vue'),
    meta: { section: 'worker' },
    children: [
      { path: '', name: 'worker-home', component: () => import('@/views/w/Dashboard.vue'), meta: { title: '工作台', section: 'worker' } },
      { path: 'tasks', name: 'worker-tasks', component: () => import('@/views/w/TaskSearch.vue'), meta: { title: '找任务', section: 'worker' } },
      { path: 'tasks/:id', name: 'worker-task-detail', component: () => import('@/views/w/TaskDetail.vue'), meta: { title: '任务详情', section: 'worker' } },
      { path: 'applications', name: 'worker-applications', component: () => import('@/views/w/Applications.vue'), meta: { title: '我的报名', section: 'worker' } },
      { path: 'engagements', name: 'worker-engagements', component: () => import('@/views/w/Engagements.vue'), meta: { title: '我的履约', section: 'worker' } },
      { path: 'engagements/:id', name: 'worker-engagement-detail', component: () => import('@/views/w/EngagementDetail.vue'), meta: { title: '履约详情', section: 'worker' } },
      { path: 'settlements', name: 'worker-settlements', component: () => import('@/views/w/Settlements.vue'), meta: { title: '结算与打款', section: 'worker' } },
      { path: 'wallet', name: 'worker-wallet', component: () => import('@/views/w/Wallet.vue'), meta: { title: '钱包', section: 'worker' } },
      { path: 'disputes', name: 'worker-disputes', component: () => import('@/views/w/Disputes.vue'), meta: { title: '争议申诉', section: 'worker' } },
      { path: 'disputes/:id', name: 'worker-dispute-detail', component: () => import('@/views/w/DisputeDetail.vue'), meta: { title: '申诉详情', section: 'worker' } },
      { path: 'profile', name: 'worker-profile-self', component: () => import('@/views/w/Profile.vue'), meta: { title: '个人资料', section: 'worker' } },
      { path: 'reputation', name: 'worker-reputation', component: () => import('@/views/w/Reputation.vue'), meta: { title: '信誉与评价', section: 'worker' } },
      { path: 'notifications', name: 'worker-notifications', component: () => import('@/views/w/Notifications.vue'), meta: { title: '通知中心', section: 'worker' } },
      { path: 'conversations', name: 'worker-conversations', component: () => import('@/views/w/Conversations.vue'), meta: { title: '沟通消息', section: 'worker' } },
      { path: 'conversations/:id', name: 'worker-conversation-detail', component: () => import('@/views/w/ConversationDetail.vue'), meta: { title: '沟通详情', section: 'worker' } },
    ],
  },
  {
    path: '/employer',
    component: () => import('@/layouts/PortalLayout.vue'),
    meta: { section: 'employer' },
    children: [
      { path: '', name: 'employer-home', component: () => import('@/views/e/Dashboard.vue'), meta: { title: '工作台', section: 'employer' } },
      { path: 'tasks/new', name: 'employer-task-new', component: () => import('@/views/e/TaskCreate.vue'), meta: { title: '发布任务', section: 'employer' } },
      { path: 'tasks', name: 'employer-tasks', component: () => import('@/views/e/TaskList.vue'), meta: { title: '我发布的任务', section: 'employer' } },
      { path: 'tasks/:id', name: 'employer-task-detail', component: () => import('@/views/e/TaskDetail.vue'), meta: { title: '任务管理', section: 'employer' } },
      { path: 'tasks/:id/applications', name: 'employer-applications', component: () => import('@/views/e/ApplicationManage.vue'), meta: { title: '报名筛选', section: 'employer' } },
      { path: 'engagements', name: 'employer-engagements', component: () => import('@/views/e/Engagements.vue'), meta: { title: '履约看板', section: 'employer' } },
      { path: 'engagements/:id/acceptance', name: 'employer-acceptance', component: () => import('@/views/e/Acceptance.vue'), meta: { title: '验收', section: 'employer' } },
      { path: 'settlements', name: 'employer-settlements', component: () => import('@/views/e/Settlements.vue'), meta: { title: '结算记录', section: 'employer' } },
      { path: 'wallet', name: 'employer-wallet', component: () => import('@/views/e/Wallet.vue'), meta: { title: '钱包', section: 'employer' } },
      { path: 'disputes', name: 'employer-disputes', component: () => import('@/views/e/Disputes.vue'), meta: { title: '争议处理', section: 'employer' } },
      { path: 'disputes/:id', name: 'employer-dispute-detail', component: () => import('@/views/e/DisputeDetail.vue'), meta: { title: '争议详情', section: 'employer' } },
      { path: 'qualification', name: 'employer-qualification', component: () => import('@/views/e/Qualification.vue'), meta: { title: '主体资质', section: 'employer' } },
      { path: 'notifications', name: 'employer-notifications', component: () => import('@/views/e/Notifications.vue'), meta: { title: '通知中心', section: 'employer' } },
      { path: 'conversations', name: 'employer-conversations', component: () => import('@/views/e/Conversations.vue'), meta: { title: '沟通消息', section: 'employer' } },
      { path: 'conversations/:id', name: 'employer-conversation-detail', component: () => import('@/views/e/ConversationDetail.vue'), meta: { title: '沟通详情', section: 'employer' } },
    ],
  },
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { section: 'admin' },
    children: [
      { path: '', name: 'admin-home', component: () => import('@/views/a/Overview.vue'), meta: { title: '治理概览', section: 'admin' } },
      { path: 'users', name: 'admin-users', component: () => import('@/views/a/Users.vue'), meta: { title: '用户治理', section: 'admin' } },
      { path: 'qualifications', name: 'admin-qualifications', component: () => import('@/views/a/Qualifications.vue'), meta: { title: '资质审核', section: 'admin' } },
      { path: 'disputes', name: 'admin-disputes', component: () => import('@/views/a/Disputes.vue'), meta: { title: '争议裁决', section: 'admin' } },
      { path: 'disputes/:id', name: 'admin-dispute-detail', component: () => import('@/views/a/DisputeDecision.vue'), meta: { title: '争议详情', section: 'admin' } },
      { path: 'differences', name: 'admin-differences', component: () => import('@/views/a/Differences.vue'), meta: { title: '差异单', section: 'admin' } },
      { path: 'payouts', name: 'admin-payouts', component: () => import('@/views/a/Payouts.vue'), meta: { title: '打款处理', section: 'admin' } },
      { path: 'reconciliation', name: 'admin-reconciliation', component: () => import('@/views/a/Reconciliation.vue'), meta: { title: '对账报告', section: 'admin' } },
      { path: 'audit', name: 'admin-audit', component: () => import('@/views/a/Audit.vue'), meta: { title: '操作日志', section: 'admin' } },
      { path: 'config', name: 'admin-config', component: () => import('@/views/a/Config.vue'), meta: { title: '系统配置', section: 'admin' } },
      { path: 'conversations/:id?', name: 'admin-conversations', component: () => import('@/views/a/Conversations.vue'), meta: { title: '会话审查', section: 'admin' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/404' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.restore()
  if (to.meta.public) return true
  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 有活儿` : '有活儿'
})

export default router
