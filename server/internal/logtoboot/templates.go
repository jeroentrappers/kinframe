package logtoboot

// SMTP connector ID shipped with Logto OSS (packages/connectors/connector-smtp).
const smtpConnectorID = "simple-mail-transfer-protocol"

type emailTemplate struct {
	UsageType   string `json:"usageType"`
	ContentType string `json:"contentType"`
	Subject     string `json:"subject"`
	Content     string `json:"content"`
}

// emailTemplates are the English verification-code emails. Logto substitutes
// {{code}}; the connector requires Register, SignIn, ForgotPassword and
// Generic. Codes expire after 10 minutes (Logto's default).
func emailTemplates() []emailTemplate {
	const footer = "\n\nThe code expires in 10 minutes. If you didn't ask for it, you can ignore this email.\n\n— kinframe"
	t := func(usage, subject, intro string) emailTemplate {
		return emailTemplate{
			UsageType:   usage,
			ContentType: "text/plain",
			Subject:     subject,
			Content:     intro + "\n\n    {{code}}" + footer,
		}
	}
	return []emailTemplate{
		t("SignIn", "Your kinframe sign-in code: {{code}}", "Hi,\n\nUse this code to sign in to kinframe:"),
		t("Register", "Your kinframe sign-up code: {{code}}", "Welcome to kinframe!\n\nUse this code to finish creating your account:"),
		t("ForgotPassword", "Your kinframe account recovery code: {{code}}", "Hi,\n\nUse this code to recover your kinframe account:"),
		t("Generic", "Your kinframe verification code: {{code}}", "Hi,\n\nYour kinframe verification code is:"),
		t("BindNewIdentifier", "Confirm your new email for kinframe: {{code}}", "Hi,\n\nUse this code to confirm the new email address for your kinframe account:"),
		t("UserPermissionValidation", "Your kinframe verification code: {{code}}", "Hi,\n\nUse this code to confirm it's you before changing your kinframe account:"),
		t("MfaVerification", "Your kinframe verification code: {{code}}", "Hi,\n\nUse this code to finish signing in to kinframe:"),
		t("BindMfa", "Your kinframe verification code: {{code}}", "Hi,\n\nUse this code to turn on two-step verification for kinframe:"),
	}
}
