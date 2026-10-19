# 🎬 Movie Ticket Booking System

A backend for booking movie tickets, built so that **two people can never end up
with the same seat**, even when they click it at the same moment.

Users browse shows, hold seats, pay, and get a QR code ticket that's scanned at
the cinema entrance. Everyone viewing a show sees seats get taken in real time.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-brightgreen)
![MySQL](https://img.shields.io/badge/MySQL-8.4-blue)
![Redis](https://img.shields.io/badge/Redis-7-red)
![WebSocket](https://img.shields.io/badge/WebSocket-STOMP-purple)

---

## ✨ Features

- 🎟️ **Seat holds**: pick up to 10 seats and they're reserved for you for 5
  minutes while you pay. If two users grab the same seat, exactly one wins.
- 💳 **Payments**: Razorpay integration (test mode), plus a built-in mock gateway
  for local development. A payment confirmed twice is handled once.
- ⚡ **Live seat map**: seats change colour instantly for everyone viewing the
  show when they're held, released, or sold.
- 📱 **QR tickets**: every confirmed booking gets a QR code, valid for one entry
  only.
- 🔐 **Secure login**: JWT authentication with refresh tokens, plus admin-only
  endpoints for managing movies, shows, refunds and gate check-in.
- 📖 **API docs**: every endpoint is documented in Swagger UI.

## 🛠️ Tech Stack

| | |
|---|---|
| **Backend** | Java 21, Spring Boot 4.1, Spring Security, Spring Data JPA |
| **Database** | MySQL |
| **Seat locking** | Redis + Lua scripts |
| **Real-time** | WebSocket (STOMP) |
| **Auth** | JWT |
| **Payments** | Razorpay |
| **QR codes** | ZXing |
| **Docs & testing** | Swagger / OpenAPI, JUnit 5, Mockito |

## 🧠 How It Works

```
 Pick seats  ──►  Hold (Redis, 5 min)  ──►  Pay  ──►  Confirm (MySQL)  ──►  QR ticket
                         │                                  │
                         └────── live updates to every viewer (WebSocket) ──────┘
```

1. **Hold**: selected seats are locked in Redis in one atomic step, so all of
   them are held or none are. Holds expire on their own if the user walks away.
2. **Pay**: a pending booking is created and the user is sent to the payment
   page.
3. **Confirm**: once payment succeeds, a single database transaction re-checks
   the seats and marks them sold. The hold is released only after that commit,
   so there's never a moment where the seat is up for grabs.
4. **Ticket**: the user downloads a QR code; staff scan it at the gate, and a
   second scan of the same ticket is rejected.

**Why Redis _and_ MySQL?** MySQL stores what's permanent (sold seats,
bookings). Redis stores what's temporary (who's holding which seat right now),
with automatic expiry, so no cleanup jobs are needed.

## 📊 By the Numbers

| | |
|---|---|
| REST endpoints | **25** |
| Unit tests | **89** |
| Database entities | **8** |
| Max seats per hold | **10** |
| Seat hold time | **5 minutes** |


## 🔌 API at a Glance

| Area | Endpoints |
|---|---|
| **Auth** | register, login, refresh token, logout |
| **Browse** | list movies, list shows, view seat map |
| **Seats** | hold seats, release seats, view my holds |
| **Bookings** | create, view, cancel, confirm payment |
| **Tickets** | QR code, printable ticket |
| **Admin** | manage movies, shows & seats, validate tickets, issue refunds |
| **Live** | WebSocket `/ws` for seat and booking updates |


```
