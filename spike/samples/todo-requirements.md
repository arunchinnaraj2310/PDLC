# Software Requirements Specification (SRS): Enterprise Fleet & Logistics Platform

**Document Version:** 1.0.0  
**Status:** Approved / Baseline  
**Author:** Product & Systems Architecture Team  
**Last Updated:** October 30, 2026  

---

## Table of Contents
1. [Introduction & Purpose](#1-introduction--purpose)
2. [Scope of the System](#2-scope-of-the-system)
3. [Stakeholders & User Personas](#3-stakeholders--user-personas)
4. [Functional Requirements](#4-functional-requirements)
5. [Data & Storage Requirements](#5-data--storage-requirements)
6. [External & User Interfaces](#6-external--user-interfaces)
7. [Non-Functional Requirements](#7-non-functional-requirements)
8. [Assumptions, Dependencies & Constraints](#8-assumptions-dependencies--constraints)
9. [Glossary](#9-glossary)

---

## 1. Introduction & Purpose

### 1.1 Document Purpose
This document defines the complete functional, technical, and operational requirements for the **Enterprise Fleet & Logistics Platform (EFLP)**. It serves as the single source of truth for engineering, QA, and product management teams.

### 1.2 System Background
The legacy dispatch system suffers from high latency, lack of real-time GPS streaming, and manual routing bottlenecks. The EFLP replaces it with a cloud-native, event-driven real-time logistics engine.

### 1.3 High-Level Goals
* Reduce average delivery routing calculation time by **80%**.
* Support **50,000+ concurrent active vehicle trackers** with sub-second telemetry updates.
* Ensure **99.99% system availability** for core dispatch operations.

---

## 2. Scope of the System

### 2.1 In-Scope Deliverables
* Real-time GPS vehicle tracking and telemetry ingestion pipeline.
* Automated dynamic route optimization engine.
* Driver mobile application (iOS/Android) for job execution and proof-of-delivery (PoD).
* Web-based dispatch and command center dashboard.
* Billing and ERP webhook integration layer.

### 2.2 Out-of-Scope Deliverables
* In-house physical hardware manufacturing of GPS trackers (utilizes third-party OBD-II / cellular IoT devices).
* Direct payroll processing (handled via downstream ADP/Workday exports).

---

## 3. Stakeholders & User Personas

| Persona | Role / Responsibility | Key System Interaction |
| :--- | :--- | :--- |
| **Fleet Dispatcher** | Manages daily routes, handles exceptions, assigns urgent jobs. | Web Dashboard (Map view, drag-and-drop assignment) |
| **Field Driver** | Executes assigned deliveries, captures signatures and photos. | Mobile App (Turn-by-turn view, PoD capture) |
| **System Administrator** | Manages user access, API keys, system health, and audit logs. | Admin Console / CLI / Kubernetes RBAC |

---

## 4. Functional Requirements

### 4.1 Real-Time Telemetry Ingestion
* **REQ-TEL-001:** The system **must** ingest MQTT/HTTPS telemetry packets from field devices containing `vehicle_id`, `timestamp`, `latitude`, `longitude`, `speed`, and `engine_status`.
* **REQ-TEL-002:** The ingestion pipeline **must** process and persist incoming telemetry points within a maximum latency of **< 500ms** under normal load.

### 4.2 Dynamic Route Optimization
* **REQ-RTE-001:** The routing engine **must** calculate multi-stop vehicle trajectories accounting for real-time traffic, vehicle load capacity, and driver shift limits.
* **REQ-RTE-002:** Dispatchers **must** be able to manually override or lock specific route segments, triggering a partial re-optimization of remaining stops.

### 4.3 Proof-of-Delivery (PoD)
* **REQ-POD-001:** The mobile app **must** allow drivers to capture digital signatures and high-resolution photographic evidence offline, queuing payloads for sync upon network restoration.

### 4.4 Identity & Authentication Engine
* **REQ-AUTH-001: Multi-Factor Authentication (MFA)**  
  The system **must** require MFA for all backend administrative and dispatcher users. It **must** support Time-based One-Time Passwords (TOTP) via authenticator apps (e.g., Google Authenticator, Authy) and FIDO2/WebAuthn hardware keys.
* **REQ-AUTH-002: Single Sign-On (SSO) Federation**  
  The platform **must** support enterprise identity provider federation using SAML 2.0 and OpenID Connect (OIDC) protocols (e.g., Okta, Azure AD, Ping Identity).
* **REQ-AUTH-003: Token-Based Session Management**  
  Upon successful authentication, the system **must** issue a cryptographically signed JSON Web Token (JWT) consisting of a short-lived `access_token` (15-minute expiry) and a securely stored, rotatable `refresh_token` (7-day expiry).
* **REQ-AUTH-004: Role-Based Access Control (RBAC)**  
  The application **must** enforce strict RBAC permissions across all API endpoints and UI pathways.
  
  | Role | Permissions / Scope |
  | :--- | :--- |
  | **System Admin** | Full read/write across all tenants, global configuration, audit log viewing. |
  | **Fleet Dispatcher** | Read/write for tenant-specific routes, drivers, and orders. Cannot modify system settings. |
  | **Field Driver** | Read-only for assigned jobs, write-only for telemetry and proof-of-delivery (PoD) uploads. |

* **REQ-AUTH-005: Automated Session Invalidation**  
  The web and mobile applications **must** automatically invalidate tokens and log out users after **30 minutes of inactivity**.

---

## 5. Data & Storage Requirements

### 5.1 Data Architecture & Entity Relationship
* **Telemetry Data:** Stored in a time-series database (e.g., TimescaleDB / Apache Cassandra) with a hot-retention window of 30 days and cold archive to S3/Parquet format.
* **Core Entity Data (Users, Fleets, Orders):** Stored in a relational database (PostgreSQL 16+) with strict ACID compliance.

### 5.2 Retention & Archiving
* Active operational data retained online for **24 months**.
* Completed delivery logs moved to cold object storage after **365 days** to comply with auditing regulations.

---

## 6. External & User Interfaces

### 6.1 User Interfaces
* **Web Portal:** Responsive single-page application (React 19 / TypeScript) supporting modern Chromium-based browsers.
* **Mobile App:** Native-feel cross-platform app (React Native / Swift / Kotlin) supporting offline-first SQLite local caching.

### 6.2 System APIs
* **REST/GraphQL API:** Public-facing API secured via OAuth 2.0 / OpenID Connect.
* **Webhooks:** Outbound webhook notifications for `order.created`, `order.delivered`, and `vehicle.geofence_breach`.

---

## 7. Non-Functional Requirements

### 7.1 Security & Compliance
* **NFR-SEC-001:** All data in transit **must** be encrypted using TLS 1.3.
* **NFR-SEC-002:** All data at rest **must** use AES-256 encryption.
* **NFR-SEC-003:** The platform **must** maintain SOC 2 Type II and GDPR compliance standards (including right-to-be-forgotten PII scrubbing).

### 7.2 Performance & Scalability
* **NFR-PER-001:** API p99 response time **must not** exceed **200ms** for read operations and **500ms** for write operations.
* **NFR-PER-002:** The system **must** scale horizontally via Kubernetes (EKS/GKE) to handle a 3x surge in peak holiday traffic without manual intervention.

### 7.3 Availability & Disaster Recovery
* **NFR-AVL-001:** System uptime SLA **must** be **99.99%** measured monthly (excluding scheduled maintenance windows).
* **NFR-DR-001:** RPO (Recovery Point Objective) **must be < 1 minute**; RTO (Recovery Time Objective) **must be < 15 minutes** via multi-region hot-standby failover.

### 7.4 Identity Security & Token Hardening
* **NFR-SEC-004: Password Hardening Policies**  
  For local standard accounts, the identity service **must** enforce password complexity using Argon2id or bcrypt hashing with a minimum work factor of 12. Passwords must be at least 14 characters, containing mixed-case alphanumeric text and symbols.
* **NFR-SEC-005: Mobile Token Storage**  
  The driver mobile application **must** isolate and store session tokens using hardware-backed secure enclaves: **iOS Keychain** and **Android Keystore/EncryptedSharedPreferences System**.
* **NFR-SEC-006: Rate Limiting & Brute Force Prevention**  
  The public authentication gateway `/api/v1/auth/login` **must** enforce a strict rate limit of **5 attempts per minute per IP or username**. Exceeding this threshold triggers an immediate 15-minute account lock and logs a high-severity security event.
* **NFR-SEC-007: Token Revocation Pipeline**  
  The architecture **must** include a distributed token-revocation backchannel (e.g., using a Redis cluster cluster layer) to instantly block compromised JWTs before their natural expiry when an admin invokes a `global_signout` or changes a user's permission set.

  ---

## 8. Assumptions, Dependencies & Constraints

### 8.1 Assumptions
* Cellular network coverage (4G/5G) is available across **>98%** of active operating routes.
* End-users utilize modern hardware devices (smartphones manufactured within the last 3 years).

### 8.2 External Dependencies
* Google Maps / Mapbox Enterprise SDK for geocoding and vector tile rendering.
* AWS / GCP managed infrastructure services.

---

## 9. Glossary

| Term | Definition |
| :--- | :--- |
| **Geofence** | A virtual perimeter for a real-world geographic area. |
| **PoD** | Proof of Delivery; verified digital record that a shipment reached its destination. |
| **Telematics** | Interdisciplinary field combining telecommunications and informatics for vehicle tracking. |
