<script setup lang="ts">
import { NConfigProvider, NDialogProvider, NMessageProvider, dateZhCN, zhCN, type GlobalThemeOverrides } from 'naive-ui'

const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#171512',
    primaryColorHover: '#2f2a24',
    primaryColorPressed: '#000000',
    primaryColorSuppl: '#171512',
    infoColor: '#171512',
    infoColorHover: '#2f2a24',
    borderRadius: '10px',
    borderRadiusSmall: '8px',
    fontFamily: 'inherit',
    textColorBase: '#171512',
  },
}
</script>

<template>
  <n-config-provider :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
    <n-dialog-provider>
      <n-message-provider>
        <RouterView />
      </n-message-provider>
    </n-dialog-provider>
  </n-config-provider>
</template>
