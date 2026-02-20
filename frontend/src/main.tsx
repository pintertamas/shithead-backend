import React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import AppRoutes from "./app/routes";
import "./app/styles/theme.css";
import "./app/styles/layout.css";

const basename = import.meta.env.BASE_URL === "/" ? undefined : import.meta.env.BASE_URL;

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <BrowserRouter basename={basename}>
      <AppRoutes />
    </BrowserRouter>
  </React.StrictMode>
);
