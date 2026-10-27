import { SignupForm } from "./signup/SignupForm";

const form = new SignupForm();

document.querySelector<HTMLFormElement>("#signup")?.addEventListener("submit", async (event) => {
  event.preventDefault();
  const result = await form.submit();
  const status = document.querySelector("#status");
  if (status) status.textContent = result.ok ? "Welcome aboard!" : result.error ?? "Something went wrong";
});
