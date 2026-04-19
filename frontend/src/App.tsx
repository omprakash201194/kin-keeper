import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import LoginPage from './pages/LoginPage'
import ChatPage from './pages/ChatPage'
import DocumentsPage from './pages/DocumentsPage'
import CategoriesPage from './pages/CategoriesPage'
import MembersPage from './pages/MembersPage'
import ContactsPage from './pages/ContactsPage'
import ConversationsPage from './pages/ConversationsPage'
import AssetsPage from './pages/AssetsPage'
import RemindersPage from './pages/RemindersPage'
import NutritionPage from './pages/NutritionPage'
import SettingsPage from './pages/SettingsPage'
import Layout from './components/Layout'
import ProtectedRoute from './components/ProtectedRoute'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/"
          element={
            <ProtectedRoute>
              <Layout />
            </ProtectedRoute>
          }
        >
          <Route index element={<Navigate to="/chat" replace />} />
          <Route path="chat" element={<ChatPage />} />
          <Route path="documents" element={<DocumentsPage />} />
          <Route path="categories" element={<CategoriesPage />} />
          <Route path="members" element={<MembersPage />} />
          <Route path="contacts" element={<ContactsPage />} />
          <Route path="conversations" element={<ConversationsPage />} />
          <Route path="assets" element={<AssetsPage />} />
          <Route path="reminders" element={<RemindersPage />} />
          <Route path="nutrition" element={<NutritionPage />} />
          <Route path="settings" element={<SettingsPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
