# The guard that replaces the enforced spend limit (FZ-204, D-37).
#
# Activating advanced features is what lifts the service control policy behind `OI-43` and
# makes CI able to deploy at all. It also **removes the enforced spend limit**, which until
# now has been the only thing standing between a mistake and an unbounded bill — for free,
# and without anybody configuring it.
#
# So this is not belt-and-braces. It is the replacement for a control that activation takes
# away, which is why `D-37` requires it the same day and why it lives in `shared/`: applied
# first, for both postures, before there is anything to spend money on.
#
# **An alert is weaker than the limit it replaces.** The limit refused; a budget only tells
# somebody. That downgrade is the price of CI and is stated here rather than discovered from
# a bill.

resource "aws_budgets_budget" "monthly" {
  name         = "${local.name}-monthly"
  budget_type  = "COST"
  limit_amount = var.budget_limit_usd
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # 80% of the limit, on spend that has already happened. At the default this fires around
  # $32 against `D-35`'s projected $18 a month — high enough not to cry wolf, low enough to
  # arrive while the month can still be salvaged.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_alert_email]
  }

  # And the same limit on the *forecast*, which is the one that matters. Something left
  # running at 3am shows up here days before it shows up above, and the whole point of
  # replacing an enforced limit is to still find out early.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_alert_email]
  }
}
