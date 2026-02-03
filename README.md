# Using Mistral AI with OpenRouter (BYOK)

This guide explains how to obtain an API key from **Mistral AI Studio** and configure it inside **OpenRouter** using the **Bring Your Own Key (BYOK)** integration. Once completed, you can use Mistral models through OpenRouter in this application.

---

## Prerequisites

* An active **Mistral AI** account
* An active **OpenRouter** account
* Internet access and a modern web browser

---

## Step 1: Create a Mistral AI API Key

<img width="237" height="186" alt="image" src="https://github.com/user-attachments/assets/1897a080-e716-4032-a021-da609bc34402" />
<img width="1151" height="434" alt="image" src="https://github.com/user-attachments/assets/cf657145-d55c-43b4-9f9f-ee98cc1e7e0c" />

1. Open **Mistral AI Studio** in your browser.
2. Sign in or create a new account if you do not already have one.
3. Navigate to the **API Keys** or **Settings** section.
4. Click **Create new API key**.
5. Give the key a recognizable name (e.g. `openrouter-byok`).
6. Copy the generated API key and store it securely.

> ⚠️ **Important:** Treat this API key like a password. Do not share it publicly or commit it to source control.

---

## Step 2: Add the Mistral Key to OpenRouter (BYOK)

<img width="146" height="329" alt="image" src="https://github.com/user-attachments/assets/829347e8-9330-43d7-abd4-f52cd0e4de00" />
<img width="237" height="129" alt="image" src="https://github.com/user-attachments/assets/249e7419-d83e-4011-a682-11ae00e5b198" />
<img width="750" height="159" alt="image" src="https://github.com/user-attachments/assets/d12b4465-6897-4475-9168-da3a94634a52" />

1. Open the **OpenRouter Dashboard**.
2. Go to **Settings → Integrations (BYOK)**.
3. Locate **Mistral AI** in the list of supported providers.
4. Click **Add Key** (or **Configure**, depending on UI).
5. Paste your **Mistral AI API key** into the field.
6. Save the configuration.

Once saved, OpenRouter will route requests to Mistral models using your personal API key.

---

## Step 3: Verify Model Availability

<img width="562" height="522" alt="image" src="https://github.com/user-attachments/assets/1ce8566c-e0b2-4203-98a9-a44b62fb060a" />

After adding the key:

1. Go to **Models** in the OpenRouter dashboard.
2. Confirm that **Mistral models** (e.g. `mistral-small`, `mistral-medium`, `mistral-large`) are available.
3. If models do not appear immediately, refresh the page or log out and back in.

---

## Step 4: Security Settings

<img width="237" height="132" alt="image" src="https://github.com/user-attachments/assets/1facc14b-7f4c-4c46-97a1-0bf35f93466d" />
<img width="1012" height="490" alt="image" src="https://github.com/user-attachments/assets/9a8299a5-9a75-4e45-9ca5-be014b990f2f" />

1. Go to **Privacy and Guardrails** in the OpenRouter settings.
2. Enable the first 3 options. 

---

## Notes & Best Practices

* **Billing & Limits:** All usage costs and rate limits are governed by your Mistral AI account when using BYOK.
* **Model Quality:** Different Mistral models vary in speed, cost, and output quality. Experiment to find the best fit.
* **Key Rotation:** If a key is compromised, revoke it immediately in Mistral AI Studio and generate a new one.
* **Stability:** If requests fail, re-check the BYOK configuration and ensure the key is still active.

---

## Troubleshooting

**Models not showing up**

* Ensure the Mistral API key is correctly saved in OpenRouter.
* Verify your Mistral account has API access enabled.

**Authentication errors**

* Recreate the API key in Mistral AI Studio.
* Remove and re-add the key in OpenRouter Integrations.

**Unexpected costs**

* Monitor usage in both OpenRouter and Mistral dashboards.

---

## Security Reminder

This app never stores your raw provider API keys outside of OpenRouter. However, you are fully responsible for managing and monitoring your own API usage when BYOK is enabled.
