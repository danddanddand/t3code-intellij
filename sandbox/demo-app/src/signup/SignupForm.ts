import { post, type ApiResult } from "../api/client";

export interface SignupInput {
  name: string;
  email: string;
  password: string;
  acceptTerms: boolean;
}

export interface Account {
  id: string;
  name: string;
  email: string;
}

export class SignupForm {
  private input: SignupInput = { name: "", email: "", password: "", acceptTerms: false };
  private submitting = false;

  update(field: keyof SignupInput, value: string | boolean): void {
    this.input = { ...this.input, [field]: value };
  }

  get values(): Readonly<SignupInput> {
    return this.input;
  }

  async submit(): Promise<ApiResult<Account>> {
    if (this.submitting) {
      return { ok: false, error: "Already submitting" };
    }
    this.submitting = true;
    try {
      // TODO: validate the input before it reaches the API.
      return await post<Account>("/accounts", {
        name: this.input.name.trim(),
        email: this.input.email.trim().toLowerCase(),
        password: this.input.password,
      });
    } finally {
      this.submitting = false;
    }
  }
}
