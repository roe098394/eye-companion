# Giving Pip a bigger brain on your PC

At home, Pip can do its thinking on your Windows laptop instead of the tablet. The tablet still
does the eyes, listening and voice. Away from home (or if the laptop is off) it automatically
uses its own brain again.

## 1. Install Ollama (free)

1. Go to **ollama.com**, download **Ollama for Windows** and install it.
2. Open **Command Prompt** (Start → type `cmd`) and download a brain:

   ```
   ollama pull llama3.2:3b
   ```

   About 2 GB, and good on most laptops. If your laptop has **16 GB of RAM or more**, this one is cleverer:

   ```
   ollama pull qwen2.5:7b
   ```

## 2. Let the tablet reach it

Still in Command Prompt:

```
setx OLLAMA_HOST 0.0.0.0
```

Then **quit Ollama** (right-click the llama icon by the clock → Quit) and **start it again** from the Start menu.

When Windows asks whether to allow Ollama on the network, choose **Private networks → Allow**.
If it never asks, run this in Command Prompt *opened as administrator*:

```
netsh advfirewall firewall add rule name="Ollama for Pip" dir=in action=allow protocol=TCP localport=11434 profile=private
```

Make sure your home Wi-Fi is set as a **Private** network in Windows
(Settings → Network & internet → Wi-Fi → your network → Private).

## 3. Connect the tablet

On the tablet: **long-press → Use my PC's brain → Search my Wi-Fi**.
Pip will say which model it's using. While the PC brain is answering, the top line shows "· PC brain".

Ollama starts with Windows, so as long as the laptop is on and awake, Pip will use it.

## If it can't find the PC

- Both devices must be on the same Wi-Fi.
- Check Ollama is running (llama icon by the clock).
- Try **Type address** instead: find the laptop's address with `ipconfig` in Command Prompt
  (the "IPv4 Address", e.g. 192.168.1.20).
