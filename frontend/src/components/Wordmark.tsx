import styles from './Wordmark.module.css'

/**
 * The FreezeHub wordmark — option `2c` from the design deck (`FZ-196`).
 *
 * Two parts, and they are separable on purpose. The **point** is the mark itself: a
 * magenta full stop after the name, in the same ink the product already uses for "a
 * restriction is in force", so the logo says the thing the interface says. The
 * **rules** above and below are the masthead furniture, and they only exist at the
 * sizes the deck actually drew.
 *
 * So a caller that has its own type scale asks for the point alone and keeps its size;
 * a caller that wants the masthead asks for the rules and takes the deck's size with
 * them. That is why `rules` is opt-in rather than the default: the sign-in screen sets
 * 30px deliberately (option `1i`), and a variant the deck never drew is not this
 * story's to invent.
 */
export function Wordmark({
  rules,
  negative = false,
}: {
  rules?: 'masthead' | 'nav' | 'compact'
  negative?: boolean
}) {
  const classes = [styles.wordmark]
  if (rules) classes.push(styles[rules])
  if (negative) classes.push(styles.negative)

  return (
    <span className={classes.join(' ')}>
      FreezeHub<span className={styles.point} aria-hidden="true">.</span>
    </span>
  )
}
