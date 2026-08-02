import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { ErrorBoundary } from './components/ErrorBoundary';
import { DevCustomerPreview } from './pages/DevCustomerPreview';
import 'tdesign-react/esm/style/index.js';
import './index.css';

document.title = '智能客服 Agent';
const RootPage = import.meta.env.DEV && window.location.pathname === '/__customer-preview'
  ? DevCustomerPreview
  : App;

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <ErrorBoundary>
        <RootPage />
      </ErrorBoundary>
    </BrowserRouter>
  </React.StrictMode>,
);
