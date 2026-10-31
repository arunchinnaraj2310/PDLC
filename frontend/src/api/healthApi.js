import { apiRequest } from './apiClient.js'

export const getHealth = () => apiRequest('/health')
