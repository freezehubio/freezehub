# Human authentication (06-security.md). The application validates JWTs against this
# pool's JWKS endpoint; FZ-046 will also call AdminCreateUser against it to provision
# invited users.

resource "aws_cognito_user_pool" "main" {
  name = local.name

  # Email is the identifier, matching how the application resolves a user. Note the
  # product's own rule differs: email is unique per organization, not globally, so a pool
  # per deployment (not per tenant) is what keeps that consistent.
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  password_policy {
    minimum_length                   = 12
    require_lowercase                = true
    require_uppercase                = true
    require_numbers                  = true
    require_symbols                  = true
    temporary_password_validity_days = 7
  }

  # Administrators create users (06-security.md: admin-provisioned bootstrap plus
  # in-product invite). There is no self-service signup, and this enforces it rather than
  # relying on the absence of a signup screen.
  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  # Lite tier (decision D-6). Advanced threat protection is an Essentials/Plus feature and
  # is deliberately not configured.

  deletion_protection = "ACTIVE"
}

resource "aws_cognito_user_pool_client" "frontend" {
  name         = "${local.name}-frontend"
  user_pool_id = aws_cognito_user_pool.main.id

  # A browser cannot keep a secret, so there is none to leak.
  generate_secret = false

  explicit_auth_flows = [
    "ALLOW_USER_SRP_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
  ]

  allowed_oauth_flows                  = ["code"]
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_scopes                 = ["email", "openid", "profile"]
  supported_identity_providers         = ["COGNITO"]

  callback_urls = ["https://${var.domain_name}/signin"]
  logout_urls   = ["https://${var.domain_name}/signin"]

  access_token_validity  = 1
  id_token_validity      = 1
  refresh_token_validity = 30

  token_validity_units {
    access_token  = "hours"
    id_token      = "hours"
    refresh_token = "days"
  }

  # Without this, a wrong password and an unknown user return different errors, which
  # turns the sign-in form into a way to enumerate who has an account.
  prevent_user_existence_errors = "ENABLED"
}

resource "aws_cognito_user_pool_domain" "main" {
  domain       = local.name
  user_pool_id = aws_cognito_user_pool.main.id
}
